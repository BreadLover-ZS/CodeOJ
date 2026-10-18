package com.codeoj.codeojbackendgateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real YAML predicate and rewrite filter, without Nacos or a listening server. */
class ContestRouteTest {
    @Test @SuppressWarnings("unchecked")
    void aliasesMatchRootAndDetailAndRewriteToQuestionServiceContext() throws Exception {
        Map<String,Object> route;
        try(InputStream input=getClass().getResourceAsStream("/application.yml")) {
            Map<String,Object> document=new Yaml().load(input);
            Map<String,Object> spring=(Map<String,Object>)document.get("spring");
            Map<String,Object> cloud=(Map<String,Object>)spring.get("cloud");
            Map<String,Object> gateway=(Map<String,Object>)cloud.get("gateway");
            route=((List<Map<String,Object>>)gateway.get("routes")).stream().filter(r->"codeoj-contest-api".equals(r.get("id"))).findFirst().get();
        }
        String predicate=((List<String>)route.get("predicates")).get(0).substring("Path=".length());
        PathRoutePredicateFactory.Config paths=new PathRoutePredicateFactory.Config();paths.setPatterns(Arrays.asList(predicate.split(",")));
        String filter=((List<String>)route.get("filters")).get(0).substring("RewritePath=".length());String[] values=filter.split(",",2);
        RewritePathGatewayFilterFactory.Config rewrite=new RewritePathGatewayFilterFactory.Config();rewrite.setRegexp(values[0].trim());rewrite.setReplacement(values[1].trim());
        for(String path:Arrays.asList("/api/contests","/api/contests/123/submissions","/api/submissions/456","/api/admin/operations/456")) {
            MockServerWebExchange exchange=MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
            assertTrue(new PathRoutePredicateFactory().apply(paths).test(exchange),path);
            AtomicReference<String> forwarded=new AtomicReference<>();new RewritePathGatewayFilterFactory().apply(rewrite).filter(exchange,next->{forwarded.set(next.getRequest().getURI().getPath());return Mono.empty();}).block();
            assertEquals("/api/question"+path.substring("/api".length()),forwarded.get());
        }
    }
}
