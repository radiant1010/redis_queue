package com.test.redis.demo.user.service;

import com.test.redis.demo.user.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    // 사용자 추가
    public boolean userInsert(List<UserDTO> users) {
        log.debug("[사용자_추가] 작업 성공");
        return true;
    }

    // 사용자 수정
    public boolean userUpdate(List<UserDTO> users) {
        log.debug("[사용자_수정] 작업 성공");
        return true;
    }

    // 사용자 삭제
    public boolean userDelete(List<UserDTO> users) {
        log.debug("[사용자_삭제] 작업 성공");
        return true;
    }
}
