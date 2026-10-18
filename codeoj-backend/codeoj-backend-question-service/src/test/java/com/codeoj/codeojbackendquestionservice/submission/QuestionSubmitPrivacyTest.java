package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.*;
import com.codeoj.codeojbackendquestionservice.service.impl.QuestionSubmitServiceImpl;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionSubmitPrivacyTest {
    private QuestionSubmit submission() { QuestionSubmit s=new QuestionSubmit();s.setId(1L);s.setUserId(10L);s.setCode("private-source");s.setJudgeInfo("{}");return s; }
    @Test void revokedAdminSessionCannotReadOthersCodeThroughLegacyListProjection() {
        ExecutionRepository repository=mock(ExecutionRepository.class);UserFeignClient users=mock(UserFeignClient.class);
        when(users.isAdmin(any())).thenReturn(true);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN_ERROR)).when(repository).requireAccount(99,"admin",false);
        QuestionSubmitServiceImpl service=new QuestionSubmitServiceImpl();ReflectionTestUtils.setField(service,"reliableEnabled",true);
        ReflectionTestUtils.setField(service,"executionRepository",repository);ReflectionTestUtils.setField(service,"userFeignClient",users);
        User stale=new User();stale.setId(99L);stale.setUserRole("admin");
        assertNull(service.getQuestionSubmitVO(submission(),stale).getCode());
    }
    @Test void ownerCanReadOwnCodeWithoutAdminPrivilege() {
        ExecutionRepository repository=mock(ExecutionRepository.class);QuestionSubmitServiceImpl service=new QuestionSubmitServiceImpl();
        ReflectionTestUtils.setField(service,"reliableEnabled",true);ReflectionTestUtils.setField(service,"executionRepository",repository);
        User owner=new User();owner.setId(10L);owner.setUserRole("user");
        assertEquals("private-source",service.getQuestionSubmitVO(submission(),owner).getCode());
        verify(repository,never()).requireAccount(anyLong(),anyString(),anyBoolean());
    }
}
