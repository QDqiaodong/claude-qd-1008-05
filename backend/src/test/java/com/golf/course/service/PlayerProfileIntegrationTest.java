package com.golf.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.dto.EntryRequest;
import com.golf.course.entity.Player;
import com.golf.course.entity.TeeTime;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.TeeTimeRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 球员资料编辑回归测试（H2 MySQL 模式）：
 * 必填资料（姓名 / 编号）服务端统一去空格并校验非空、空值拒绝且原资料保留、
 * 可选字段去空格 / 空串置 NULL、乐观版本过期 409、JPA @Version 并发兜底、
 * 任一持久化异常整笔回滚不留半成品、合法修改在列表 / 详情 / 赛事相关显示一致生效、
 * HTTP 链路 400 / 409 口径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayerProfileIntegrationTest {

    @Autowired private PlayerService playerService;
    @Autowired private HandicapService handicapService;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentEntryRepository entryRepository;
    @Autowired private TournamentEntryService entryService;
    @Autowired private TeeTimeRepository teeTimeRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM handicap_history_round");
        jdbc.execute("DELETE FROM handicap_history");
        jdbc.execute("DELETE FROM tournament_start_attempt");
        jdbc.execute("DELETE FROM tournament_round_hole");
        jdbc.execute("DELETE FROM scorecard_event");
        jdbc.execute("DELETE FROM scorecard_hole");
        jdbc.execute("DELETE FROM scorecard");
        jdbc.execute("DELETE FROM tournament_entry");
        jdbc.execute("DELETE FROM score");
        jdbc.execute("DELETE FROM tee_time");
        jdbc.execute("DELETE FROM leaderboard_snapshot");
        jdbc.execute("DELETE FROM leaderboard");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM player");
    }

    private Player mkPlayer(String no, String name) {
        return mkPlayer(no, name, 12, "13800000000", "老备注");
    }

    private Player mkPlayer(String no, String name, int handicap, String phone, String note) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName(name);
        p.setHandicap(handicap);
        p.setPhone(phone);
        p.setNote(note);
        p.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(p);
    }

    private Player patch(Player p) {
        Player in = new Player();
        in.setPlayerNo(p.getPlayerNo());
        in.setName(p.getName());
        in.setHandicap(p.getHandicap());
        in.setPhone(p.getPhone());
        in.setNote(p.getNote());
        return in;
    }

    /** 单次落库建档（v0）；不要对 mkPlayer 结果再 save/flush，否则测试自身会把版本推高一格。 */
    private Player seedPlayer(String no, String name, int handicap, String phone, String note) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName(name);
        p.setHandicap(handicap);
        p.setPhone(phone);
        p.setNote(note);
        p.setCreatedAt(LocalDateTime.now());
        return playerRepository.saveAndFlush(p);
    }

    // ---------------- 1. 空姓名 / 空编号：服务端拒绝，返回明确错误并保留原资料 ----------------

    @Test
    void blankOrWhitespaceName_isRejectedAndOriginalPlayerIsKept() {
        Player p = mkPlayer("P-101", "林浩");
        long versionBefore = p.getVersion();
        for (String blank : List.of("", "   ", "\t\n")) {
            Player in = patch(p);
            in.setName(blank);
            assertThatThrownBy(() -> playerService.update(p.getId(), in, p.getVersion()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("姓名");
            Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
            assertThat(reloaded.getName()).as("空姓名必须拒绝且保留原姓名").isEqualTo("林浩");
            assertThat(reloaded.getVersion()).as("拒绝不得推进版本号").isEqualTo(versionBefore);
        }
    }

    @Test
    void nullNameAndBlankPlayerNo_areRejectedExplicitly() {
        Player p = mkPlayer("P-102", "陈雨桐");
        Player noName = patch(p);
        noName.setName(null);
        assertThatThrownBy(() -> playerService.update(p.getId(), noName, p.getVersion()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("姓名")
                .hasMessageContaining("原资料已保留");

        Player blankNo = patch(p);
        blankNo.setPlayerNo("   ");
        assertThatThrownBy(() -> playerService.update(p.getId(), blankNo, p.getVersion()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编号")
                .hasMessageContaining("原资料已保留");

        assertThat(playerRepository.findById(p.getId()).orElseThrow().getPlayerNo()).isEqualTo("P-102");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getName()).isEqualTo("陈雨桐");
    }

    // ---------------- 2. 必填资料服务端统一 trim 后落库 ----------------

    @Test
    void requiredFields_areTrimmedBeforePersist() {
        Player p = mkPlayer("P-103", "王启明");
        Player in = patch(p);
        in.setPlayerNo("  P-103-NEW  ");
        in.setName("   王启明新名  ");
        in.setPhone("  139-1111-2222  ");
        in.setNote("  新备注  ");
        Player saved = playerService.update(p.getId(), in, p.getVersion());
        assertThat(saved.getPlayerNo()).isEqualTo("P-103-NEW");
        assertThat(saved.getName()).isEqualTo("王启明新名");
        assertThat(saved.getPhone()).isEqualTo("139-1111-2222");
        assertThat(saved.getNote()).isEqualTo("新备注");
        assertThat(saved.getVersion()).isEqualTo(1L);

        Player reloaded = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getPlayerNo()).isEqualTo("P-103-NEW");
        assertThat(reloaded.getName()).isEqualTo("王启明新名");
    }

    @Test
    void whitespaceOnlyOptionalFields_areNormalizedToNull() {
        Player p = mkPlayer("P-104", "赵子涵", 20, "13800000001", "有备注");
        Player in = patch(p);
        in.setPhone("   ");
        in.setNote("\t ");
        Player saved = playerService.update(p.getId(), in, p.getVersion());
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getNote()).isNull();
    }

    // ---------------- 3. 持久化异常：整笔恢复，不留下半更新状态 ----------------

    @Test
    void duplicatePlayerNo_isRejectedAndOriginalPlayerIsKept() {
        Player a = mkPlayer("P-201", "甲", 10, "111", "备注甲");
        mkPlayer("P-202", "乙", 11, "222", "备注乙");
        Player in = patch(a);
        in.setPlayerNo("P-202");
        in.setName("甲改了名");
        assertThatThrownBy(() -> playerService.update(a.getId(), in, a.getVersion()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编号已存在");
        Player reloaded = playerRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getPlayerNo()).isEqualTo("P-201");
        assertThat(reloaded.getName()).isEqualTo("甲");
        assertThat(reloaded.getVersion()).isEqualTo(a.getVersion());
    }

    @Test
    void flushTimePersistenceFailure_rollsBackWholeEdit_noPartialFieldUpdate() {
        Player a = mkPlayer("P-203", "原姓名", 10, "原电话", "原备注");

        // name 超过 VARCHAR(48)：前置校验放行，flush 时数据库拒绝（DataIntegrityViolation）。
        // 此时姓名 / 差点 / 电话 / 备注已在持久化实体上改好，事务必须整体回滚——
        // 不能留下「电话、备注已改、姓名没改成」的半成品。
        Player in = patch(a);
        in.setName("x".repeat(49));
        in.setHandicap(50);
        in.setPhone("新电话999");
        in.setNote("新备注999");
        assertThatThrownBy(() -> playerService.update(a.getId(), in, a.getVersion()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(BizException.class);

        Player reloaded = playerRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getName()).as("姓名恢复").isEqualTo("原姓名");
        assertThat(reloaded.getHandicap()).as("差点不能只更新一半").isEqualTo(10);
        assertThat(reloaded.getPhone()).as("电话不能只更新一半").isEqualTo("原电话");
        assertThat(reloaded.getNote()).as("备注不能只更新一半").isEqualTo("原备注");
        assertThat(reloaded.getVersion()).as("回滚不得推进版本号").isEqualTo(a.getVersion());
    }

    // ---------------- 4. 过期版本：409 数据已变化，本次不生效，可直接拿到当前资料 ----------------

    @Test
    void staleExpectedVersion_isRejectedAsConflict_andStaleEditDoesNotApply() {
        Player p = seedPlayer("P-301", "原名", 12, "13800000000", "老备注");
        long v0 = p.getVersion();
        // 管理员 A 先保存成功：v0 -> v1
        Player first = patch(p);
        first.setName("A 改的名");
        Player afterA = playerService.update(p.getId(), first, v0);
        assertThat(afterA.getVersion()).isEqualTo(v0 + 1);

        // 管理员 B 仍拿着 v0 提交：必须 409，消息带版本与「重新读取」，data 是当前球员
        Player stale = patch(p);
        stale.setName("B 改的名");
        assertThatThrownBy(() -> playerService.update(p.getId(), stale, v0))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getMessage()).contains("数据已变化").contains("v" + v0)
                            .contains("v" + (v0 + 1)).contains("重新读取");
                    assertThat(ex.getData()).isInstanceOf(Player.class);
                    Player current = (Player) ex.getData();
                    assertThat(current.getName()).isEqualTo("A 改的名");
                    assertThat(current.getVersion()).isEqualTo(v0 + 1);
                });

        Player finalState = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(finalState.getName()).as("过期操作不得落库").isEqualTo("A 改的名");
        assertThat(finalState.getVersion()).isEqualTo(v0 + 1);
    }

    // ---------------- 5. 并发提交（漏传 expectedVersion）：JPA @Version 兜底，只一次生效 ----------------

    @Test
    void concurrentUpdates_withoutExpectedVersion_onlyOneCommits_loserWholeRollback() throws Exception {
        // 单次落库建档（v0）
        Player p = seedPlayer("P-401", "并发原名", 10, "电话0", "备注0");
        final Long id = p.getId();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<String> loserErrors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // 故意不传 expectedVersion（null），由 @Version 在 flush 时兜底
                    Player in = new Player();
                    in.setPlayerNo("P-401");
                    in.setName("并发改名" + idx);
                    in.setHandicap(10 + idx);
                    in.setPhone("电话" + idx);
                    in.setNote("备注" + idx);
                    playerService.update(id, in, null);
                    success.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 乐观锁失败（JPA 版本号或约束冲突转换）：竞争失败方必须整笔回滚
                    boolean optimistic = e instanceof ObjectOptimisticLockingFailureException
                            || e.getClass().getSimpleName().contains("OptimisticLock");
                    if (optimistic) {
                        conflict.incrementAndGet();
                    }
                    loserErrors.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(success.get()).as("loser errors: %s", loserErrors).isEqualTo(1);
        assertThat(conflict.get()).as("loser errors: %s", loserErrors).isEqualTo(threads - 1);

        Player winner = playerRepository.findById(id).orElseThrow();
        // 赢家的姓名 / 差点 / 电话 / 备注必须来自同一次提交，不允许混装
        int winIdx = Integer.parseInt(winner.getName().substring(4));
        assertThat(winner.getName()).isEqualTo("并发改名" + winIdx);
        assertThat(winner.getHandicap()).isEqualTo(10 + winIdx);
        assertThat(winner.getPhone()).isEqualTo("电话" + winIdx);
        assertThat(winner.getNote()).isEqualTo("备注" + winIdx);
        assertThat(winner.getVersion()).isEqualTo(1L);
    }

    // ---------------- 6. 合法修改：列表 / 详情 / 赛事相关显示一起用新资料 ----------------

    @Test
    void validEdit_propagatesToListDetailEntryTeeTimeAndHandicapView() {
        Player p = seedPlayer("P-501", "旧姓名", 12, "旧电话", null);

        Tournament t = new Tournament();
        t.setTournamentNo("T-P501");
        t.setName("球员改名联动赛事");
        t.setStartDate(LocalDate.of(2026, 11, 1));
        t.setEndDate(LocalDate.of(2026, 11, 2));
        t.setStatus("报名中");
        t.setLocation("测试场");
        t.setCreatedAt(LocalDateTime.now());
        tournamentRepository.save(t);
        entryService.add(t.getId(), new EntryRequest(p.getId(), 1));

        TeeTime tee = new TeeTime();
        tee.setPlayer(p);
        tee.setPlayDate(LocalDate.of(2026, 11, 5));
        tee.setSlot("07:00");
        tee.setStatus("已订");
        tee.setCreatedAt(LocalDateTime.now());
        teeTimeRepository.save(tee);

        // 一次合法编辑
        Player in = patch(p);
        in.setPlayerNo("P-501N");
        in.setName("新姓名");
        playerService.update(p.getId(), in, p.getVersion());

        // 列表
        assertThat(playerService.list()).extracting(Player::getName).contains("新姓名");
        // 详情（GET）
        assertThat(playerService.get(p.getId()).getName()).isEqualTo("新姓名");
        assertThat(playerService.get(p.getId()).getPlayerNo()).isEqualTo("P-501N");
        // 赛事参赛名单显示
        TournamentEntry entry = entryRepository.findByTournamentIdOrderByIdAsc(t.getId()).get(0);
        assertThat(entry.getPlayerName()).isEqualTo("新姓名");
        assertThat(entry.getPlayerNo()).isEqualTo("P-501N");
        // 开球时间显示
        assertThat(teeTimeRepository.findAll().get(0).getPlayerName()).isEqualTo("新姓名");
        // 差点详情视图（赛事 / 差点相关显示都从 Player 关联取姓名）
        assertThat(handicapService.playerView(p.getId()).playerName()).isEqualTo("新姓名");
    }

    // ---------------- 7. HTTP 链路：空姓名 400 明确拒绝；过期版本 409 带当前球员 ----------------

    @Test
    void http_blankNameReturns400_andStaleVersionReturns409WithCurrentPlayer() throws Exception {
        Player p = seedPlayer("P-601", "接口原名", 12, "13800000000", "老备注");

        // 7.1 空姓名：400 + ok:false + 明确中文提示
        String blankBody = objectMapper.writeValueAsString(java.util.Map.of(
                "playerNo", "P-601", "name", "   ", "handicap", 12,
                "phone", "13800000000", "note", "备注"));
        ResponseEntity<String> rejected = rest.exchange(
                "/api/players/" + p.getId(),
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(blankBody, jsonHeaders()),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(rejected.getBody());
        assertThat(json.path("ok").asBoolean()).isFalse();
        assertThat(json.path("message").asText()).contains("姓名");
        // 原资料保留：再 GET 仍是旧姓名
        ResponseEntity<String> afterReject = rest.getForEntity("/api/players/" + p.getId(), String.class);
        assertThat(objectMapper.readTree(afterReject.getBody()).path("data").path("name").asText())
                .isEqualTo("接口原名");

        // 7.2 过期版本：409 + data 内为当前球员
        String staleBody = objectMapper.writeValueAsString(java.util.Map.of(
                "playerNo", "P-601", "name", "过期名", "handicap", 12));
        org.springframework.http.HttpEntity<String> staleReq = new org.springframework.http.HttpEntity<>(
                staleBody, jsonHeaders());
        ResponseEntity<String> conflict = rest.exchange(
                "/api/players/" + p.getId() + "?expectedVersion=999",
                org.springframework.http.HttpMethod.PUT,
                staleReq,
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode conflictJson = objectMapper.readTree(conflict.getBody());
        assertThat(conflictJson.path("ok").asBoolean()).isFalse();
        assertThat(conflictJson.path("message").asText()).contains("数据已变化");
        assertThat(conflictJson.path("data").path("name").asText()).isEqualTo("接口原名");
        assertThat(conflictJson.path("data").path("version").asLong()).isEqualTo(p.getVersion());

        // 7.3 合法更新成功并推进版本
        String okBody = objectMapper.writeValueAsString(java.util.Map.of(
                "playerNo", " P-601 ", "name", " 接口新名 ", "handicap", 15, "phone", "", "note", " "));
        org.springframework.http.HttpEntity<String> okReq = new org.springframework.http.HttpEntity<>(
                okBody, jsonHeaders());
        ResponseEntity<String> ok = rest.exchange(
                "/api/players/" + p.getId() + "?expectedVersion=" + p.getVersion(),
                org.springframework.http.HttpMethod.PUT,
                okReq,
                String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode okJson = objectMapper.readTree(ok.getBody());
        assertThat(okJson.path("ok").asBoolean()).isTrue();
        assertThat(okJson.path("data").path("name").asText()).isEqualTo("接口新名");
        assertThat(okJson.path("data").path("playerNo").asText()).isEqualTo("P-601");
        assertThat(okJson.path("data").path("phone").isNull());
        assertThat(okJson.path("data").path("note").isNull());
        assertThat(okJson.path("data").path("version").asLong()).isEqualTo(p.getVersion() + 1);
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return h;
    }
}
