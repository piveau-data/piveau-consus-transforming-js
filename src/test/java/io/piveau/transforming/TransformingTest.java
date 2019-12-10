package io.piveau.transforming;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@DisplayName("Testing the transformer")
@ExtendWith(VertxExtension.class)
class TransformingTest {

    @BeforeEach
    void startImporter(Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new MainVerticle(), testContext.succeeding(response -> testContext.verify(testContext::completeNow)));
    }

    @Test
    @DisplayName("pipe receiving")
    void sendPipe(Vertx vertx, VertxTestContext testContext) {
        vertx.fileSystem().readFile("test-pipe.json", result -> {
            if (result.succeeded()) {
                JsonObject pipe = new JsonObject(result.result());
                testContext.completeNow();
            } else {
                testContext.failNow(result.cause());
            }
        });
    }

}
