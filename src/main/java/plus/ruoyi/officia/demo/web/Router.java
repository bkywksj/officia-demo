package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简路由表：把「路径 → 处理器」注册成 Map，取代巨型 switch。
 *
 * <p>好处：新增端点只需 {@code add("/api/xxx", handler)} 一行，不必再往一个几百行的
 * switch 里塞分支；各能力的端点也能按模块分文件注册（见 {@code XxxRoutes.register}）。</p>
 *
 * @author officia-demo
 */
final class Router {

    /** 一个端点处理器：拿到请求与已解析的查询参数，自行写响应。 */
    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange ex, Map<String, String> query) throws Exception;
    }

    /** 保持注册顺序，便于按模块分组阅读与排查。 */
    private final Map<String, Handler> routes = new LinkedHashMap<>();

    /** 注册一个端点；重复路径直接失败，避免静默覆盖。 */
    Router add(String path, Handler handler) {
        if (routes.putIfAbsent(path, handler) != null) {
            throw new IllegalStateException("端点重复注册: " + path);
        }
        return this;
    }

    /**
     * 分发；命中返回 true。
     *
     * @return false 表示无匹配端点（上层转 404）
     */
    boolean dispatch(HttpExchange ex, String path, Map<String, String> query) throws Exception {
        Handler h = routes.get(path);
        if (h == null) {
            return false;
        }
        h.handle(ex, query);
        return true;
    }

    /** 已注册端点数（健康检查/自检用）。 */
    int size() {
        return routes.size();
    }

    /** 已注册端点路径（测试用）。 */
    java.util.Set<String> paths() {
        return routes.keySet();
    }
}
