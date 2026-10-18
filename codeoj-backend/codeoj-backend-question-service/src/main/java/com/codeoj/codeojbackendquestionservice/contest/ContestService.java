package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendquestionservice.submission.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

/** Full-transaction retries happen outside the transactional proxy. */
@Service
public class ContestService {
    private final ContestTransactions transactions;
    private final ExecutionRepository executions;
    public ContestService(ContestTransactions transactions,ExecutionRepository executions) { this.transactions=transactions;this.executions=executions; }
    public <T> T retry(Supplier<T> operation) {
        for(int attempt=0;attempt<3;attempt++) {
            try { return operation.get(); }catch(TransientDataAccessException error) { if(attempt==2) { throw error; } }
        }
        throw new IllegalStateException("UNREACHABLE");
    }
    public long submit(long id,ContestSubmission request,String key,long user) {
        try { return retry(()->transactions.submit(id,request,key,user)); }
        catch(DuplicateKeyException error) {
            ExecutionRecord prior=executions.byKey(user,key);
            if(prior!=null) { return SubmissionAcceptance.replay(prior,ContestTransactions.submissionHash(id,request)); }throw error;
        }
    }
}
