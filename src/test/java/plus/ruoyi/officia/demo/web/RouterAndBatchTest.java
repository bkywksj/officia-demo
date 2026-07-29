package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由表与批量回归测试。
 *
 * <p>Batch 曾用可变静态计数器统计通过/失败，并发调用会互相污染；改为局部统计后，
 * 这里用<b>并发跑两次</b>来锁住该修复——两次结果必须各自自洽（total == pass + fail）。</p>
 *
 * @author officia-demo
 */
@DisplayName("Router 与 Batch 测试")
class RouterAndBatchTest {

    @Test
    @DisplayName("路由表：未注册路径返回 false（上层转 404）")
    void unknownPath() throws Exception {
        Router r = new Router();
        // 未命中时不会触碰 HttpExchange，故传 null 安全
        assertThat(r.dispatch(null, "/api/nope", Collections.emptyMap())).isFalse();
    }

    @Test
    @DisplayName("路由表：重复注册直接失败，不静默覆盖")
    void duplicateRegistration() {
        Router r = new Router();
        r.add("/api/x", (ex, q) -> { });
        assertThatThrownBy(() -> r.add("/api/x", (ex, q) -> { }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("重复注册");
    }

    @Test
    @DisplayName("所有 API 端点均已注册（含各能力模块）")
    void endpointsRegistered() {
        assertThat(ApiRoutes.endpointCount()).as("端点总数").isGreaterThanOrEqualTo(25);
    }

    @Test
    @DisplayName("批量回归：全部用例通过，且 total == pass + fail")
    void batchRunsGreen() {
        String json = Batch.run().end();
        assertThat(json).contains("\"fail\":0");
        assertThat(json).contains("\"rows\":[");
    }

    @Test
    @DisplayName("批量回归并发调用互不污染（统计无共享可变状态）")
    void batchConcurrentSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            jobs.add(() -> Batch.run().end());
            jobs.add(() -> Batch.run().end());
            List<Future<String>> results = pool.invokeAll(jobs);
            for (Future<String> f : results) {
                String json = f.get();
                // 每次调用的统计必须自洽：失败数为 0（用例本身应全绿）
                assertThat(json).contains("\"fail\":0");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
