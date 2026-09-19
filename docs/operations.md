# 실행 및 운영 가이드

기본 실행은 Mock 호출과 최종 실패 메일 로그를 사용합니다. 아래 DB 저장·멱등성 설명 및 Webhook 외부 발송은 database 프로필의 추가 예제에 해당합니다. 기본 Mock 모드에는 DB Bean이 없고 Webhook 외부 발송도 비활성화됩니다. Redis 상태 관리·운영 API·보존 정책은 공통으로 사용합니다.

[프로젝트 소개로 돌아가기](../README.md)

## Mock 데모 실행

JDK 17과 실행 중인 Docker 엔진이 필요합니다. Redis와 큐의 상태 변경은 실제로 실행하고, 외부 업무 호출·메일 발송은 로그로 대체합니다. 기본 모드에서는 DB를 생성하거나 Webhook을 발송하지 않습니다.

```sh
docker compose up -d --wait
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=demo"
```

macOS / Linux:

```sh
bash ./gradlew bootRun --args='--spring.profiles.active=demo'
```

`demo` 프로필은 아래 작업 3개를 등록합니다. 주소는 외부 요청의 목적지가 아닌 Mock 시나리오 식별값입니다.

| 예제 데이터의 email | 동작 | 최종 상태 |
|---|---|---|
| `success@example.invalid` | 첫 호출 성공 | SUCCEEDED |
| `retry@example.invalid` | 2회 실패 후 3번째 호출 성공 | SUCCEEDED |
| `fail@example.invalid` | 3회 모두 실패, 최종 실패 알림 로그 | DLQ |

```text
[MOCK CALL] jobId=..., attempt=1/3
[MOCK FAILURE] jobId=..., attempt=1
[MOCK RETRY] jobId=..., nextAttempt=2
...
[MOCK SUCCESS] jobId=..., attempt=3

# 모든 호출 시도가 실패한 작업에만 출력
[MOCK MAIL] final failure: jobId=..., reason=... (no email sent)
```

새 Redis 데이터에서 실행하면 작업 2개가 성공하고 1개가 DLQ에 남습니다. `demo` 없이 실행하면 작업을 자동 등록하지 않습니다. `Ctrl+C`로 정상 종료합니다.

## 실제 서비스 연결 지점

| 인터페이스 | 현재 구현 | 연동 시 역할 |
|---|---|---|
| `UserJobClient` | `MockUserJobClient` | WebClient 호출, 오류 분류, 호출 제한 시간·재시도 정책 |
| `FailureNotification` | `LoggingFailureNotification` | 최종 실패 시 회사 메일 서비스 호출 |

기본 Mock Bean을 실제 어댑터 Bean으로 교체하면 됩니다. WebClient 구현은 `subscribe()`만 호출하고 성공을 반환하지 말고, **재시도를 포함한 최종 결과를 Handler에 전달**해야 합니다. 작업 복구·재처리 시 같은 jobId를 전달하고, 중복 반영 방지는 실제 작업을 수행하는 서비스에서 처리해야 합니다.

Mock은 호출 흐름을 보여주는 예제이므로 실제 HTTP 상태 코드별 재시도 정책이나 원격 서비스의 멱등성을 구현하지는 않습니다. 종료로 인한 interrupt는 최종 실패 메일 대상으로 취급하지 않고 Processing에 남겨 복구합니다. 알림 어댑터가 예외를 던져도 작업은 DLQ로 이동합니다. 실제 메일의 전달 보장·별도 발송 재시도는 연결하는 메일 서비스의 책임입니다.

## Graceful Shutdown

종료 시 Consumer의 다음 작업 획득을 멈추고 현재 작업을 최대 45초 기다립니다. 시간이 지나면 취소와 interrupt를 요청하고 5초 동안 정리 완료를 기다립니다. 실제 Consumer 스레드가 끝나야 Spring에 종료 완료를 알립니다.

이 대기 시간의 합은 `spring.lifecycle.timeout-per-shutdown-phase`(기본 60초)보다 짧아야 합니다. Spring의 제한 시간을 넘긴 뒤 `@PreDestroy`에서만 interrupt하면 Redis 연결 팩토리가 이미 종료되어 `LettuceConnectionFactory has been STOPPED` 또는 `was destroyed` 오류가 발생할 수 있기 때문입니다.

취소된 작업은 Processing에 남기며, Handler가 interrupt를 무시하고 뒤늦게 성공을 반환해도 큐의 완료 처리를 하지 않습니다. 외부 Handler 자체가 취소를 무시하거나 별도로 Redis를 사용한다면 강제 종료와 자원 접근까지 보장할 수 없으므로, 연동하는 호출에도 제한 시간과 취소 처리가 필요합니다.

호출 재시도와 DLQ 재처리는 별개입니다. Mock 호출은 최초 호출 포함 최대 3회, 기본 100ms 간격으로 시도합니다. 내부 호출 재시도는 큐의 attempts를 늘리지 않으며, DLQ 수동 재처리는 queue.policy.max-attempts를 따릅니다.

## 저장 구조

| 저장소 | 내용 |
|---|---|
| Redis `queue:staging:user-jobs:<jobId>` | 작업별 사용자 데이터 JSON |
| Redis `queue:pending:user-jobs` | 대기 중 작업 명세 List |
| Redis `queue:processing:user-jobs` | 가져왔지만 완료 확인되지 않은 작업 List |
| Redis `queue:dlq:user-jobs` | 실패한 작업 명세 List |
| Redis `queue:job:<jobId>` | 상태, 시도 횟수, 실패 코드, 시도별 이력, 보존 기한 Hash |
| Redis `queue:jobs`, `queue:retention` | 조회·만료 처리를 위한 Sorted Set |
| Redis `queue:counters` | 성공·실패 처리 횟수 |
| DB `processed_job` | jobId 고유 키와 처리 시각. 자동 삭제하지 않음 |
| DB `app_user` | 해당 작업으로 저장한 email·displayName. 비밀번호를 취급하지 않음 |

작업 명세는 `{"jobType":"USER_ADD","jobId":"..."}` 형식입니다. `StringRedisTemplate`과 명시적인 Jackson 타입으로 읽고 씁니다. 등록, 성공, DLQ 이동, 재처리, 정리는 Lua에서 상태 검사와 변경을 함께 수행합니다. Lua는 오류 시 롤백하지 않으므로 변경 전에 키 타입과 전제 상태를 검사합니다.

## 장애 복구와 멱등성

`UserWriter.insertOnce()`는 `@Transactional` 메서드입니다. 처리 이력과 사용자 행을 모두 저장하거나 모두 롤백합니다. jobId 고유 제약으로 동시 중복 저장도 방지합니다.

| 종료 시점 | 재시작 후 동작 |
|---|---|
| Pending에서 가져온 직후 | Processing을 Pending으로 복구하고 처리 |
| DB 트랜잭션 커밋 전 | 미커밋 데이터는 롤백되고 재실행 시 저장 |
| DB 커밋 후 Redis 성공 처리 전 | DB 처리 이력을 확인해 저장을 생략하고 Redis 상태만 완료 |
| Redis 성공 처리 후 | Processing에 없으므로 복구 대상이 아님 |

자동 복구는 **애플리케이션 시작 시**, 이전 인스턴스가 종료됐다는 전제에서만 수행합니다. 이미 대기 중인 작업보다 복구 작업을 먼저 처리합니다. 실행 중 Consumer가 Redis 오류로 멈추면 알림을 보내고, 원인을 해결한 뒤 앱을 재시작합니다. 손상되거나 메타데이터가 없는 메시지는 삭제하지 않고 오류로 남깁니다.

이는 작업의 재실행을 허용하면서 **동일 jobId의 DB 반영을 한 번으로 제한**하는 설계입니다. 외부 HTTP 호출·메일 발송 같은 부수 효과까지 한 번으로 보장하지는 않습니다. 새 Handler를 추가할 때도 별도의 멱등성 구현이 필요합니다.

등록 API를 다시 호출하면 새 jobId가 생깁니다. 등록 응답을 잃은 요청의 중복 제출을 막는 클라이언트 요청 키 기능은 포함하지 않습니다.

## DLQ 조회와 재처리

실패 시 작업 데이터는 보존하고 실패 코드, 마지막 실패 시각, 실행 횟수, 시도별 결과를 기록합니다. 예를 들어 `MISSING_STAGING`, `INVALID_USER`, `HANDLER_NOT_FOUND`, `HANDLER_RETURNED_FALSE` 또는 예외 클래스 이름을 확인할 수 있습니다. 메타데이터와 알림에는 제출한 사용자 원문을 넣지 않습니다.

수동 재처리는 다음 조건을 모두 만족해야 합니다.

- 현재 상태가 `DLQ`
- 원문 보존 기한이 지나지 않았고 Staging 데이터가 존재
- 현재 실행 횟수가 `queue.policy.max-attempts` 미만 (기본 3)

성공하면 같은 jobId를 Pending에 한 번만 등록하고 기존 만료 예약을 취소합니다. 중복 재처리 요청, 만료된 작업, 횟수 초과는 `409 Conflict`입니다. 재처리 작업은 Pending의 기존 작업 뒤에 배치합니다. 이미 지나간 실패 작업의 원래 순서를 복원하지는 않습니다.

장애 복구 실행도 `attempts`에 기록합니다. 시작 시 Processing 복구는 수동 재처리 한도와 별개로 수행하므로, DB 커밋 뒤 중단된 작업도 처리 이력을 확인할 수 있습니다. 자동 DLQ 재시도와 백오프는 제공하지 않습니다.

## 모니터링과 Webhook

기본 15초 간격으로 다음 상태를 점검합니다. HTTP API에서도 같은 지표를 조회할 수 있습니다.

- 큐별 Pending·Processing·DLQ 길이, 가장 오래된 작업의 대기 시간
- Consumer 상태: `STOPPED`, `RUNNING`, `STOPPING`, `FAILED`
- 누적 성공·실패 처리 횟수와 실패율
- DLQ 원문 만료 임박, 모니터링·정리 실패, Webhook 발송 실패 상태

실패율은 `실패 / (성공 + 실패)`이며 완료된 시도를 기준으로 합니다. 중단된 시도는 분모에 포함하지 않습니다. Redis 카운터를 초기화하면 누적값도 초기화됩니다.

`QUEUE_WEBHOOK_URL`을 설정하면 다음 형식으로 HTTP POST를 보냅니다.

```json
{"text":"DLQ failures since last notification: 2"}
```

Consumer 중단은 다음 점검에서 알리고, DLQ 실패는 미통지 건수를 묶어 보냅니다. 같은 종류의 알림은 기본 5분 동안 반복을 제한합니다. 발송 실패는 성공으로 기록하지 않고 다음 점검 때 다시 시도합니다. HTTP 연결 제한은 2초, 요청 제한은 3초입니다.

URL이 없으면 외부 발송 없이 경고 로그를 남기며, API에는 `webhook.enabled=false`로 표시합니다. 쿨다운·발송 확인 상태는 메모리에 있으므로 앱 재시작 시 기존 실패 요약을 다시 알릴 수 있습니다. 영속적인 알림 전달 보장이나 알림 서비스 자체의 가용성 감시는 포함하지 않습니다.

이 예제의 `text` JSON을 받을 수 있는 Webhook 수신 서비스를 사용하세요. 서비스별 추가 인증·다른 메시지 형식이 필요하면 발송 어댑터를 추가해야 합니다. 앱 자체가 내려간 동안의 장애 감지는 별도 외부 가용성 감시가 필요합니다.

## 보존 정책

| 대상 | 기본 정책 |
|---|---|
| Pending·Processing 원문 | 자동 삭제하지 않음. 기본 5분 이상이면 지연 알림 |
| 성공한 작업 원문 | Redis 성공 처리 시 삭제 |
| DLQ 원문 | 마지막 실패부터 30일 보존. 만료 1일 전부터 알림 대상 |
| 만료된 DLQ | 원문과 DLQ List 항목 삭제, 메타데이터는 `EXPIRED`로 유지. 재처리 불가 |
| 종료된 작업 메타데이터 | 마지막 성공·실패부터 90일 뒤 삭제 |
| DB 멱등성 처리 이력 | 자동 삭제하지 않음 |
| DB 업무 데이터 | 큐 정리 대상이 아님. 업무별 보존 정책 필요 |

Staging은 작업별 키로 분리했지만, 등록 시 무조건 TTL을 걸지는 않습니다. 만료 Sorted Set에서 매 점검 최대 100건을 읽고 Lua가 **현재 상태와 기한을 다시 확인**한 뒤 정리합니다. 재처리가 먼저 실행됐다면 정리 작업이 그 원문을 삭제하지 않습니다. 앱이 중지된 동안에는 정리도 멈추고, 재시작 후 밀린 만료 처리를 수행합니다.

## 실행하기

JDK 17과 실행 중인 Docker 엔진이 필요합니다. 프로젝트 루트에서 실행합니다.

### Redis와 예제 작업

```sh
docker compose up -d --wait
```

Redis는 로컬 `6379`에 열리고 AOF와 named volume을 사용합니다. DB는 앱 실행 시 `./data/queue.mv.db`에 생성됩니다. 앱 재시작 후 복구를 위해 Redis volume과 DB 파일을 함께 유지해야 합니다.

Windows PowerShell:

```powershell
$env:QUEUE_OPS_TOKEN = [guid]::NewGuid().ToString('N')
.\gradlew.bat bootRun --args="--spring.profiles.active=database,demo"
```

macOS / Linux:

```sh
export QUEUE_OPS_TOKEN="replace-with-your-local-token"
bash ./gradlew bootRun --args='--spring.profiles.active=database,demo'
```

`demo` 프로필은 시작할 때 예제 작업 3개를 등록합니다. 매번 새 jobId를 사용하므로 다시 실행하면 DB에도 새 작업의 행이 추가됩니다. DB 모드에서 자동 등록 없이 실행하려면 `--spring.profiles.active=database`만 지정합니다. `Ctrl+C`로 정상 종료합니다.

### 운영 API 사용

HTTP 서버는 기본 `127.0.0.1:8080`에 바인딩됩니다. 모든 HTTP 요청에 `Authorization: Bearer <QUEUE_OPS_TOKEN>`이 필요합니다. 토큰을 설정하지 않으면 API는 `503`, 잘못된 토큰은 `401`을 반환합니다. 데모 Consumer 실행 자체는 토큰 없이도 가능합니다.

아래 예시는 앱을 실행한 것과 같은 토큰을 설정한 별도 PowerShell에서 실행합니다.

```powershell
$headers = @{ Authorization = "Bearer $env:QUEUE_OPS_TOKEN" }

# 작업 등록: 실제 DB에 저장할 예제 데이터
$job = Invoke-RestMethod -Method Post -Uri http://localhost:8080/ops/jobs/users `
  -Headers $headers -ContentType 'application/json' `
  -Body '[{"email":"demo@example.com","displayName":"Demo User"}]'

# 상태와 실패 이력
Invoke-RestMethod -Uri "http://localhost:8080/ops/jobs/$($job.jobId)" -Headers $headers
Invoke-RestMethod -Uri http://localhost:8080/ops/queues -Headers $headers
Invoke-RestMethod -Uri 'http://localhost:8080/ops/dlq?limit=20' -Headers $headers
```

실패를 재현하려면 이메일 형식이 잘못된 데이터를 제출할 수 있습니다. `INVALID_USER`로 DLQ에 남습니다. 원인이 그대로인 작업을 재처리하면 다시 실패하는 것이 정상입니다.

```powershell
# DLQ 작업의 jobId를 넣고 원인을 해결한 뒤 실행
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/ops/jobs/JOB_ID/retry' -Headers $headers
```

| 메서드 | 경로 | 역할 |
|---|---|---|
| POST | `/ops/jobs/users` | 사용자 작업 등록, `202`와 jobId 반환 |
| GET | `/ops/jobs/{id}` | 상태·시도별 이력·보존 기한 조회 |
| GET | `/ops/jobs?offset=0&limit=50` | 최근 작업 목록 |
| GET | `/ops/dlq?queue=USER&offset=0&limit=50` | DLQ 메타데이터 조회 |
| POST | `/ops/jobs/{id}/retry` | 같은 jobId로 수동 재처리 |
| GET | `/ops/queues` | 큐·Consumer·실패율·Webhook 상태 |

조회 `limit`은 1~100입니다. 원문 데이터와 DB 사용자 목록을 공개하는 API는 제공하지 않습니다.

Redis 종료:

```sh
docker compose down
```

### 주요 설정

| 설정 | 기본값 |
|---|---|
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `QUEUE_DB_URL` | `jdbc:h2:file:./data/queue;DB_CLOSE_ON_EXIT=FALSE` |
| `QUEUE_DB_PASSWORD` | 빈 값. 로컬 데모 DB용 |
| `QUEUE_OPS_TOKEN` | 빈 값: API 비활성화 |
| `QUEUE_WEBHOOK_URL` | 빈 값: 외부 알림 비활성화 |
| `queue.consumer.enabled` | `true` |
| `queue.maintenance.enabled` | `true` |
| `queue.maintenance.interval-ms` | `15000` |
| `queue.policy.max-attempts` | `3` |
| `queue.policy.dlq-retention` / `metadata-retention` | `30d` / `90d` |
| `queue.policy.expiry-warning` / `stuck-threshold` | `1d` / `5m` |
| `queue.policy.alert-cooldown` | `5m` |
| `spring.lifecycle.timeout-per-shutdown-phase` | `60s` |
| `queue.shutdown.grace-period` | `45s`: 진행 중 작업의 정상 완료 대기 |
| `queue.shutdown.interrupt-wait` | `5s`: 취소·interrupt 이후 정리 대기 |

보존 기간 설정은 이후 상태 전환에 적용됩니다. 이미 기록된 작업의 기한을 일괄 변경하지 않습니다. 메타데이터 보존 기간은 원문 보존 기간보다 길어야 합니다.

두 Consumer 종료 대기 시간의 합은 Spring의 lifecycle 단계 제한 시간보다 짧아야 합니다. 조건을 만족하지 않으면 시작 시 설정 오류로 거부합니다. 완료 대기 시간이 지나면 Spring이 Redis 종료 단계로 넘어가기 전에 Consumer를 취소합니다. Handler가 취소를 무시하면 실제 스레드 종료 전까지 완료 콜백을 실행하지 않으며, Spring의 최종 제한 시간 이후에는 외부 Handler의 자원 접근을 보장할 수 없습니다.

## 테스트

테스트는 Testcontainers가 띄운 별도의 Redis와 테스트용 H2 DB, 로컬 HTTP 수신 서버를 사용합니다. 개인 Redis 데이터나 외부 Webhook에는 접근하지 않습니다. Docker 엔진만 실행 중이면 됩니다.

```powershell
.\gradlew.bat clean build
```

```sh
bash ./gradlew clean build
```

핵심 검증:

- FIFO, 앞 Handler가 끝나기 전 다음 Handler가 실행되지 않는지
- 실제 Lettuce 연결 팩토리로 정상 종료 및 Spring 제한 시간 이전 interrupt·정리 검증
- interrupt를 무시한 Handler의 늦은 완료 처리 차단과 Processing 보존
- DB 일부 저장 실패 시 업무 행·처리 이력 동시 롤백
- DB 파일을 닫고 앱을 다시 시작해도 커밋된 작업의 중복 저장 방지
- DLQ 원인·이력·시도 횟수, 재처리 중복 요청과 횟수 제한
- DLQ 원문 만료·메타데이터 만료, 활성 작업과 DB 처리 이력 보존
- 운영 API 인증·조회·등록·재처리 상태 코드
- 실제 HTTP Webhook 수신, 중복 억제, 실패 후 재전송, 알림 실패 중에도 작업 처리
- Consumer 중단·적체·만료 임박 알림

결과는 `build/reports/tests/test/index.html`에 생성됩니다. GitHub Actions에 Java 17 빌드와 Docker 기반 테스트가 설정되어 있습니다. 원격 CI 실행 결과는 저장소에 반영한 뒤 확인할 수 있습니다.

DB 및 운영 기능에 더해 기본 Mock 데모의 호출 재시도·최종 실패 로그와 DB·외부 Webhook 비활성화도 테스트합니다.

## 적용 범위와 한계

- 단일 애플리케이션 인스턴스·standalone Redis 전용입니다. 분산 락·Redis Cluster를 지원하지 않습니다.
- 실패 작업을 DLQ로 보낸 뒤 다음 작업은 진행합니다. 앞 작업의 성공을 반드시 요구하는 워크플로가 아닙니다.
- 앱 종료 시 다음 반복의 작업 획득을 멈춥니다. 이미 진행 중인 5초 blocking poll이 반환한 작업은 처리할 수 있습니다. 기본 45초 대기 후 취소·interrupt를 요청하고 5초 정리를 기다립니다. 취소를 무시하는 Handler의 강제 종료는 보장하지 않습니다.
- Redis와 DB 사이에 분산 트랜잭션은 없습니다. Redis 자체 데이터 유실, 서로 다른 시점의 Redis·DB 백업 복원까지 자동 해결하지 않습니다.
- 파일 DB는 재현 가능한 로컬 예제용입니다. 다른 DB 적용 시 드라이버·스키마·트랜잭션 동작을 검증해야 합니다.
- 이전 공유 Staging Hash 형식과 새 메타데이터 형식의 자동 마이그레이션은 없습니다. 기존 대기 작업을 정리한 뒤 새 형식으로 전환해야 합니다. API 예제 DTO는 `email`, `displayName`이며 비밀번호 필드는 제거했습니다.

## 작업 유형 추가

`QueueType`과 `JobType`을 정의하고, `JobHandler` 및 `StagingManageService<T>` Bean을 등록합니다. 새 큐라면 `JobConsumerConfig`에 Consumer를 추가합니다. **Handler는 업무 저장과 jobId 이력을 같은 트랜잭션에 넣거나, 해당 업무에 맞는 멱등성을 직접 제공해야 합니다.** Handler가 성공을 반환하면 큐 계층이 Staging을 정리하므로 Handler에서 직접 삭제하지 않습니다.
