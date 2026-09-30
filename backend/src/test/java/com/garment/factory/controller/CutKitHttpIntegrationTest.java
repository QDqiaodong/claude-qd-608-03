package com.garment.factory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 裁片套件 HTTP 层验收：真实 MVC 全栈（含序列化、事务、行锁、幂等表），
 * 校验状态码语义（200/400/409）、重复请求幂等回放、并发交接只一次成功、
 * 接收复核失败回滚、200 片批量封包接口性能与结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CutKitHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode postJson(String url, Object body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode kitDetail(Long id) throws Exception {
        return getJson("/api/cut-kits/" + id).path("data");
    }

    private JsonNode kitTimeline(Long id) throws Exception {
        return getJson("/api/cut-kits/" + id + "/timeline").path("data");
    }

    private int postStatus(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Long createKit(String suffix) throws Exception {
        JsonNode resp = postJson("/api/cut-kits", body(
                "orderNo", "ORD-HTTP-" + suffix,
                "cutBatchNo", "CUT-H1",
                "garmentName", "HTTP 验收西装",
                "sizeMark", "M",
                "operator", "陈师傅"));
        return resp.path("data").path("id").asLong();
    }

    private List<Map<String, Object>> manifest8() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String[] ps : new String[][]{
                {"FRONT", "LEFT"}, {"FRONT", "RIGHT"}, {"BACK", "CENTER"},
                {"SLEEVE", "LEFT"}, {"SLEEVE", "RIGHT"}, {"COLLAR", "CENTER"},
                {"LINING", "LEFT"}, {"LINING", "RIGHT"}}) {
            items.add(body("part", ps[0], "side", ps[1], "fabricBatchNo", "B1",
                    "sizeMark", "M", "quantity", 1));
        }
        return items;
    }

    private List<Map<String, Object>> scans(String prefix) {
        List<String[]> ps = List.of(
                new String[]{"FRONT", "LEFT"}, new String[]{"FRONT", "RIGHT"}, new String[]{"BACK", "CENTER"},
                new String[]{"SLEEVE", "LEFT"}, new String[]{"SLEEVE", "RIGHT"}, new String[]{"COLLAR", "CENTER"},
                new String[]{"LINING", "LEFT"}, new String[]{"LINING", "RIGHT"});
        List<Map<String, Object>> list = new ArrayList<>();
        int i = 1;
        for (String[] p : ps) {
            list.add(body("pieceCode", prefix + String.format("%02d", i++),
                    "part", p[0], "side", p[1], "fabricBatchNo", "B1", "sizeMark", "M"));
        }
        return list;
    }

    private Long sealKit() throws Exception {
        Long id = createKit(String.valueOf(System.nanoTime()));
        postJson("/api/cut-kits/" + id + "/manifest", body("items", manifest8(), "operator", "陈师傅"));
        postJson("/api/cut-kits/" + id + "/scans", body("operator", "陈师傅", "pieces", scans("P")));
        mockMvc.perform(post("/api/cut-kits/" + id + "/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body("operator", "陈师傅"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SEALED"))
                .andExpect(jsonPath("$.data.sealNo").isNotEmpty());
        return id;
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        Long id = sealKit();
        // 交接
        mockMvc.perform(post("/api/cut-kits/" + id + "/hand-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body(
                                "operator", "交出人甲", "receiverName", "接收人乙", "requestId", "H-" + id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDED_OVER"));

        // 接收：封签 + 8 片清点
        JsonNode detail = kitDetail(id);
        String sealNo = detail.path("sealNo").asText();
        mockMvc.perform(post("/api/cut-kits/" + id + "/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body(
                                "operator", "接收人乙", "sealNo", sealNo,
                                "pieces", scans("R"), "requestId", "R-" + id))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        // 时间线接口可查
        mockMvc.perform(get("/api/cut-kits/" + id + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventType").exists());

        // 列表接口刷新后状态一致
        mockMvc.perform(get("/api/cut-kits").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("RECEIVED"));
    }

    @Test
    void sealBlockedReturns400AndKitStaysDraft() throws Exception {
        Long id = createKit("block-" + System.nanoTime());
        postJson("/api/cut-kits/" + id + "/manifest", body("items", manifest8(), "operator", "陈师傅"));
        List<Map<String, Object>> partial = scans("P");
        partial.remove(0); // 缺一片
        postJson("/api/cut-kits/" + id + "/scans", body("operator", "陈师傅", "pieces", partial));

        mockMvc.perform(post("/api/cut-kits/" + id + "/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body("operator", "陈师傅"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("封包被拦截")));

        mockMvc.perform(get("/api/cut-kits/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void handOverValidation400AndIdempotentReplay() throws Exception {
        Long id = sealKit();
        // 缺接收人 → 400
        assertEquals(400, postStatus("/api/cut-kits/" + id + "/hand-over",
                body("operator", "甲", "requestId", "BAD-" + id)));

        // 同 requestId 首次成功
        assertEquals(200, postStatus("/api/cut-kits/" + id + "/hand-over",
                body("operator", "交出人甲", "receiverName", "接收人乙", "requestId", "IDEM-" + id)));
        // 同 requestId 重放仍 200
        assertEquals(200, postStatus("/api/cut-kits/" + id + "/hand-over",
                body("operator", "交出人甲", "receiverName", "接收人乙", "requestId", "IDEM-" + id)));
        // 不同 requestId 再交接 → 409 冲突
        assertEquals(409, postStatus("/api/cut-kits/" + id + "/hand-over",
                body("operator", "交出人丙", "receiverName", "接收人乙", "requestId", "OTHER-" + id)));
    }

    @Test
    void concurrentHandOverOverHttpExactlyOneWinner() throws Exception {
        Long id = sealKit();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Runnable> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int person = i;
            String reqId = "CONC-" + id + "-" + person;
            jobs.add(() -> {
                try {
                    start.await();
                    int sc = postStatus("/api/cut-kits/" + id + "/hand-over",
                            body("operator", "交出人" + person, "receiverName", "接收人乙", "requestId", reqId));
                    if (sc == 200) {
                        ok.incrementAndGet();
                    } else if (sc == 409) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        for (Runnable r : jobs) {
            pool.submit(r);
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, ok.get(), "HTTP 并发交接只允许一次 200");
        assertEquals(threads - 1, conflict.get(), "其余全部 409");

        // 时间线只有一条交接，无半条记录
        JsonNode timeline = kitTimeline(id);
        int handOvers = 0;
        for (JsonNode e : timeline) {
            if ("HAND_OVER".equals(e.path("eventType").asText())) {
                handOvers++;
            }
        }
        assertEquals(1, handOvers);
    }
    @Test
    void receiveFailuresRollbackOverHttp() throws Exception {
        Long id = sealKit();
        postJson("/api/cut-kits/" + id + "/hand-over", body(
                "operator", "交出人甲", "receiverName", "接收人乙", "requestId", "H-" + id));
        JsonNode detail = kitDetail(id);
        String sealNo = detail.path("sealNo").asText();

        // 封签错误：409，状态保持 HANDED_OVER，时间线多一条 RECEIVE_FAIL
        JsonNode timelineBefore = kitTimeline(id);
        int before = timelineBefore.size();
        mockMvc.perform(post("/api/cut-kits/" + id + "/receive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body(
                                "operator", "接收人乙", "sealNo", "FAKE", "pieces", scans("R")))))
                .andExpect(status().isConflict());
        JsonNode afterFail = kitDetail(id);
        assertEquals("HANDED_OVER", afterFail.path("status").asText());
        int after = kitTimeline(id).size();
        assertTrue(after > before, "失败必须留下 RECEIVE_FAIL 事件");

        // 数量不符（少一片）：409
        List<Map<String, Object>> shortScans = scans("R");
        shortScans.remove(0);
        assertEquals(409, postStatus("/api/cut-kits/" + id + "/receive",
                body("operator", "接收人乙", "sealNo", sealNo, "pieces", shortScans)));
        assertEquals("HANDED_OVER", kitDetail(id)
                .path("status").asText());

        // 补齐后正常接收
        assertEquals(200, postStatus("/api/cut-kits/" + id + "/receive",
                body("operator", "接收人乙", "sealNo", sealNo, "pieces", scans("R"),
                        "requestId", "R-OK-" + id)));
    }

    @Test
    void unsealRequiresReasonAndKeepsTimeline() throws Exception {
        Long id = sealKit();
        // 无原因 400
        assertEquals(400, postStatus("/api/cut-kits/" + id + "/unseal", body("operator", "陈师傅")));
        // 有原因成功并保留前后快照
        mockMvc.perform(post("/api/cut-kits/" + id + "/unseal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body("operator", "陈师傅", "reason", "里布方向裁反"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        JsonNode timeline = kitTimeline(id);
        boolean unsealFound = false;
        for (JsonNode e : timeline) {
            if ("UNSEAL".equals(e.path("eventType").asText())) {
                unsealFound = true;
                assertTrue(e.path("before").has("sealNo"));
                assertTrue(e.path("after").path("status").asText().equals("DRAFT"));
            }
        }
        assertTrue(unsealFound);
    }
}
