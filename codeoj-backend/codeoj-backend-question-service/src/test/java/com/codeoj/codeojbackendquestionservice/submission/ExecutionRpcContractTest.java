package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendquestionservice.controller.inner.QuestionInnerController;
import com.codeoj.codeojbackendquestionservice.service.QuestionSubmitService;
import com.codeoj.codeojbackendcommon.exception.GlobalExceptionHandler;
import com.codeoj.codeojbackendserviceclient.filter.InnerApiAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.*;
import javax.servlet.FilterChain;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRpcContractTest {
    @Test void internalDatabaseFailureReturns503InsteadOfAFalseClaim() throws Exception {
        ExecutionCommands commands=mock(ExecutionCommands.class);when(commands.claim(any())).thenThrow(new IllegalStateException("database unavailable"));
        QuestionInnerController controller=new QuestionInnerController();
        ReflectionTestUtils.setField(controller,"executionCommands",commands);ReflectionTestUtils.setField(controller,"reliableEnabled",true);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ExecutionRpcAdvice(),new GlobalExceptionHandler()).build();
        mvc.perform(post("/inner/execution/claim").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("EXECUTION_AUTHORITY_UNAVAILABLE"));
    }
    @Test void genericUpdateCannotWriteNewReliableSubmission() throws Exception {
        ExecutionRepository repository=mock(ExecutionRepository.class);when(repository.find(1L,false)).thenReturn(new ExecutionRecord());
        QuestionSubmitService service=mock(QuestionSubmitService.class);QuestionInnerController controller=new QuestionInnerController();
        ReflectionTestUtils.setField(controller,"executionRepository",repository);ReflectionTestUtils.setField(controller,"questionSubmitService",service);
        ReflectionTestUtils.setField(controller,"reliableEnabled",true);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/inner/question_submit/update").contentType("application/json").content("{\"id\":1,\"status\":2}"))
                .andExpect(status().isOk()).andExpect(content().string("false"));
        verify(service,never()).updateById(any());
    }
    @Test void internalCommandRequiresExistingSecretFilter() throws Exception {
        InnerApiAuthFilter filter=new InnerApiAuthFilter();ReflectionTestUtils.setField(filter,"innerAuthSecret","test-internal-only");
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/question/inner/execution/claim");
        MockHttpServletResponse response=new MockHttpServletResponse();FilterChain chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);assertEquals(403,response.getStatus());verifyNoInteractions(chain);
    }
    @Test void idempotencyConflictIsHttp409EvenWithLegacyGlobalAdvice() throws Exception {
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new ConflictController()).setControllerAdvice(new SubmissionConflictAdvice(),new GlobalExceptionHandler()).build();
        mvc.perform(get("/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900));
    }
    @org.springframework.web.bind.annotation.RestController
    static class ConflictController {
        @org.springframework.web.bind.annotation.GetMapping("/conflict")
        public void conflict() { throw new SubmissionConflictException("IDEMPOTENCY_CONTENT_CONFLICT"); }
    }
}
