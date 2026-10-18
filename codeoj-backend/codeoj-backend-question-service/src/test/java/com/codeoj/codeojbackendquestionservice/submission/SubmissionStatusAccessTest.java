package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendmodel.model.entity.*;
import com.codeoj.codeojbackendmodel.model.vo.QuestionSubmitVO;
import com.codeoj.codeojbackendquestionservice.service.QuestionSubmitService;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SubmissionStatusAccessTest {
    private MockMvc mvc(long actor,String role,boolean exists) {
        UserFeignClient users=mock(UserFeignClient.class);QuestionSubmitService submissions=mock(QuestionSubmitService.class);
        User user=new User();user.setId(actor);user.setUserRole(role);when(users.getLoginUser(any())).thenReturn(user);
        if(exists) {
            QuestionSubmit entity=new QuestionSubmit();entity.setId(1L);entity.setUserId(10L);when(submissions.getById(1L)).thenReturn(entity);
            QuestionSubmitVO view=new QuestionSubmitVO();view.setId(1L);view.setExecutionStatus("QUEUED");
            when(submissions.getQuestionSubmitVO(entity,user)).thenReturn(view);
        }
        return MockMvcBuilders.standaloneSetup(new SubmissionStatusController(submissions,users,mock(ExecutionRepository.class))).setControllerAdvice(new ExecutionRpcAdvice()).build();
    }
    @Test void ownerCanQueryExecutionStatus() throws Exception { mvc(10,"user",true).perform(get("/submissions/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.executionStatus").value("QUEUED")); }
    @Test void otherUserCannotReadSubmission() throws Exception { mvc(11,"user",true).perform(get("/submissions/1")).andExpect(status().isForbidden()); }
    @Test void adminCanQueryButMissingRecordIs404() throws Exception {
        mvc(99,"admin",true).perform(get("/submissions/1")).andExpect(status().isOk());
        mvc(99,"admin",false).perform(get("/submissions/1")).andExpect(status().isNotFound());
    }
}
