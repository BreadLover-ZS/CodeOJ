package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.codeoj.codeojbackendmodel.model.entity.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

@Service
public class ReliableSubmissionService {
    private final SubmissionAcceptance acceptance;
    private final ExecutionRepository repository;
    public ReliableSubmissionService(SubmissionAcceptance acceptance,ExecutionRepository repository) {
        this.acceptance=acceptance; this.repository=repository;
    }

    public long accept(QuestionSubmitAddRequest request,User user) {
        if(request==null || user==null || user.getId()==null || "ban".equals(user.getUserRole())
                || request.getQuestionId()==null || request.getQuestionId()<=0 || !"java".equals(request.getLanguage())
                || request.getCode()==null || request.getCode().trim().isEmpty()
                || request.getCode().getBytes(StandardCharsets.UTF_8).length>256*1024
                || request.getRequestKey()==null || !request.getRequestKey().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_RELIABLE_SUBMISSION");
        }
        for(int attempt=0;attempt<3;attempt++) {
            try { return acceptance.accept(request,user); }
            catch(DuplicateKeyException e) {
                // The proxied transaction has rolled back completely before this lookup.
                ExecutionRecord prior=repository.byKey(user.getId(),request.getRequestKey());
                if(prior!=null) { return SubmissionAcceptance.replay(prior,SubmissionAcceptance.requestHash(request)); }
                throw e;
            } catch(TransientDataAccessException e) {
                if(attempt==2) { throw e; } // Retry the whole transaction, not a broken statement.
            }
        }
        throw new IllegalStateException("UNREACHABLE");
    }
}
