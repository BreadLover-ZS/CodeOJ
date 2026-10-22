package com.codeoj.codeojbackendcodesandbox.service.impl;

import java.io.IOException;

/** Physical resources must be absent before a durable slot can be reused. */
interface IsolationEngine {
    void removeExecution(String token) throws IOException, InterruptedException;
}
