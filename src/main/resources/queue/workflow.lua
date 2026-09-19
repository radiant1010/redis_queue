-- All state transitions for one job run atomically on a standalone Redis instance.
-- KEYS: data, metadata, pending, processing, dlq, job index, retention index, counters.
-- ARGV: operation, payload JSON, now, data JSON (or empty), error code, data TTL ms,
--       metadata TTL ms, max manual attempts.
local op, payload, now = ARGV[1], ARGV[2], tonumber(ARGV[3])
local job = cjson.decode(payload)
local function check(key, expected)
    local actual = redis.call('TYPE', key).ok
    if actual ~= 'none' and actual ~= expected then
        error('Unexpected Redis key type; expected ' .. expected)
    end
end
check(KEYS[2], 'hash')
check(KEYS[6], 'zset')
check(KEYS[7], 'zset')
check(KEYS[8], 'hash')
local state = redis.call('HGET', KEYS[2], 'status')
local function update(status)
    redis.call('HSET', KEYS[2], 'status', status, 'updatedAt', now)
end
local function member(key)
    if not redis.call('LPOS', key, payload) then error('Job missing from expected queue') end
end
if op == 'REGISTER' then
    check(KEYS[1], 'string')
    check(KEYS[3], 'list')
    if redis.call('EXISTS', KEYS[1]) == 1 or state then return -1 end
    if ARGV[4] ~= '' then redis.call('SET', KEYS[1], ARGV[4]) end
    redis.call('HSET', KEYS[2], 'jobId', job.jobId, 'jobType', job.jobType,
        'status', 'PENDING', 'attempts', 0, 'createdAt', now, 'updatedAt', now)
    redis.call('LPUSH', KEYS[3], payload)
    redis.call('ZADD', KEYS[6], now, job.jobId)
    return 1
elseif op == 'START' then
    check(KEYS[4], 'list')
    if state ~= 'PENDING' then return -1 end
    member(KEYS[4])
    local attempt = redis.call('HINCRBY', KEYS[2], 'attempts', 1)
    update('PROCESSING')
    redis.call('HSET', KEYS[2], 'attempt.' .. attempt .. '.startedAt', now)
    return 1
elseif op == 'SUCCESS' then
    check(KEYS[4], 'list')
    if state ~= 'PROCESSING' then return -1 end
    member(KEYS[4])
    local attempt = redis.call('HGET', KEYS[2], 'attempts')
    redis.call('LREM', KEYS[4], 1, payload)
    redis.call('DEL', KEYS[1])
    update('SUCCEEDED')
    redis.call('HSET', KEYS[2], 'attempt.' .. attempt .. '.outcome', 'SUCCEEDED',
        'attempt.' .. attempt .. '.finishedAt', now,
        'metadataExpiresAt', now + tonumber(ARGV[7]))
    redis.call('ZADD', KEYS[7], now + tonumber(ARGV[7]), job.jobId)
    redis.call('HINCRBY', KEYS[8], 'succeeded', 1)
    return 1
elseif op == 'FAIL' then
    check(KEYS[4], 'list')
    check(KEYS[5], 'list')
    if state ~= 'PROCESSING' then return -1 end
    member(KEYS[4])
    redis.call('LPUSH', KEYS[5], payload)
    redis.call('LREM', KEYS[4], 1, payload)
    local attempt = redis.call('HGET', KEYS[2], 'attempts')
    update('DLQ')
    redis.call('HSET', KEYS[2], 'lastFailure', ARGV[5], 'failedAt', now,
        'attempt.' .. attempt .. '.outcome', 'FAILED',
        'attempt.' .. attempt .. '.error', ARGV[5],
        'attempt.' .. attempt .. '.finishedAt', now,
        'expiresAt', now + tonumber(ARGV[6]), 'metadataExpiresAt', now + tonumber(ARGV[7]))
    redis.call('ZADD', KEYS[7], now + tonumber(ARGV[6]), job.jobId)
    redis.call('HINCRBY', KEYS[8], 'failed', 1)
    return 1
elseif op == 'RETRY' then
    check(KEYS[1], 'string')
    check(KEYS[3], 'list')
    check(KEYS[5], 'list')
    if state ~= 'DLQ' then return -1 end
    if tonumber(redis.call('HGET', KEYS[2], 'expiresAt')) <= now then return -2 end
    if tonumber(redis.call('HGET', KEYS[2], 'attempts')) >= tonumber(ARGV[8]) then return -3 end
    if redis.call('EXISTS', KEYS[1]) == 0 then return -4 end
    member(KEYS[5])
    redis.call('LPUSH', KEYS[3], payload)
    redis.call('LREM', KEYS[5], 1, payload)
    update('PENDING')
    redis.call('HDEL', KEYS[2], 'expiresAt', 'metadataExpiresAt')
    redis.call('ZREM', KEYS[7], job.jobId)
    return 1
elseif op == 'RECOVER' then
    check(KEYS[3], 'list')
    check(KEYS[4], 'list')
    if state ~= 'PROCESSING' and state ~= 'PENDING' then return -1 end
    member(KEYS[4])
    -- Append at the pop end so interrupted work precedes existing Pending jobs.
    redis.call('RPUSH', KEYS[3], payload)
    redis.call('LREM', KEYS[4], 1, payload)
    if state == 'PROCESSING' then
        local attempt = redis.call('HGET', KEYS[2], 'attempts')
        redis.call('HSET', KEYS[2], 'attempt.' .. attempt .. '.outcome', 'INTERRUPTED',
            'attempt.' .. attempt .. '.finishedAt', now)
    end
    update('PENDING')
    redis.call('HINCRBY', KEYS[2], 'recoveries', 1)
    return 1
elseif op == 'CLEANUP' then
    check(KEYS[5], 'list')
    if state == 'DLQ' and tonumber(redis.call('HGET', KEYS[2], 'expiresAt')) <= now then
        member(KEYS[5])
        redis.call('DEL', KEYS[1])
        redis.call('LREM', KEYS[5], 1, payload)
        update('EXPIRED')
        redis.call('ZADD', KEYS[7], redis.call('HGET', KEYS[2], 'metadataExpiresAt'), job.jobId)
        return 1
    elseif (state == 'EXPIRED' or state == 'SUCCEEDED')
            and tonumber(redis.call('HGET', KEYS[2], 'metadataExpiresAt')) <= now then
        redis.call('DEL', KEYS[2])
        redis.call('ZREM', KEYS[6], job.jobId)
        redis.call('ZREM', KEYS[7], job.jobId)
        return 1
    end
    return 0
end
return redis.error_reply('Unknown operation')
