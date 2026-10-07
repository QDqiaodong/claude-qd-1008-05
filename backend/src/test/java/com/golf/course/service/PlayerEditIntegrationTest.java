package com.golf.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.entity.Course;
import com.golf.course.entity.Player;
import com.golf.course.entity.Tournament;
import com.golf.course.entity.TournamentEntry;
import com.golf.course.repository.CourseRepository;
import com.golf.course.repository.PlayerRepository;
import com.golf.course.repository.TournamentEntryRepository;
import com.golf.course.repository.TournamentRepository;

/**
 * 「编辑球员」回归测试（H2 MySQL 模式）：
 *
 * <p>空值 / 纯空格必填拒绝且原资料完整保留（不再静默返回成功）；
 * 服务端统一去首尾空格；整份资料先校验后落库，校验 / 持久化异常整笔事务回滚，
 * 姓名、电话、备注不会只更新一部分；合法修改后版本自增且列表 / 差点详情 / 赛事名单
 * 一起显示新资料；多人同时编辑只有基于最新版本的一次生效，过期操作 409 带当前球员；
 * HTTP 链路：空姓名 400（ok:false 且资料不变）、过期版本 409（data 带最新球员）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayerEditIntegrationTest {

    @Autowired private PlayerService playerService;
    @Autowired private HandicapService handicapService;
    @Autowired private TournamentEntryService entryService;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentEntryRepository entryRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @SpyBean private PlayerRepository spyPlayerRepository;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM tournament_entry");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM player");
        jdbc.execute("DELETE FROM course");
        Mockito.reset(spyPlayerRepository);
    }

    private Player mkPlayer(String no, String name) {
        return playerService.create(input(no, name, 12, "13800000000", "老备注"));
    }

    /** 模拟页面提交的整份球员资料 */
    private static Player input(String no, String name, int handicap, String phone, String note) {
        Player p = new Player();
        p.setPlayerNo(no);
        p.setName(name);
        p.setHandicap(handicap);
        p.setPhone(phone);
        p.setNote(note);
        return p;
    }

    // ---------------- 1. 姓名清空 / 纯空格：明确拒绝，原资料保留，绝不再静默成功 ----------------

    @Test
    void blankName_isRejected_originalProfileUntouched() {
        Player p = mkPlayer("P-B1", "林浩");
        long v = p.getVersion();

        for (String bad : new String[] {"", "   ", "\t", "\n "}) {
            Player req = input("P-B1", bad, 20, "139", "新备注");
            assertThatThrownBy(() -> playerService.update(p.getId(), req, v))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("球员姓名不能为空");
        }

        Player stored = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("林浩");
        assertThat(stored.getPhone()).isEqualTo("13800000000");
        assertThat(stored.getNote()).isEqualTo("老备注");
        assertThat(stored.getHandicap()).isEqualTo(12);
        assertThat(stored.getVersion()).isEqualTo(v);
    }

    // ---------------- 2. 编号清空 / 纯空格同样拒绝且保留原资料 ----------------

    @Test
    void blankPlayerNo_isRejected_originalProfileUntouched() {
        Player p = mkPlayer("P-B2", "陈雨桐");
        for (String bad : new String[] {"", "   "}) {
            Player req = input(bad, "陈雨桐", 12, null, null);
            assertThatThrownBy(() -> playerService.update(p.getId(), req, p.getVersion()))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("球员编号不能为空");
        }
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getPlayerNo()).isEqualTo("P-B2");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getVersion()).isEqualTo(p.getVersion());
    }

    // ---------------- 3. 校验失败整笔回滚：姓名合法但编号撞车时，姓名/电话/备注一律不动 ----------------

    @Test
    void duplicatePlayerNo_wholeEditRolledBack_noPartialUpdate() {
        Player a = mkPlayer("P-D1", "甲球员");
        Player b = mkPlayer("P-D2", "乙球员");

        Player req = input("P-D1", "乙的新姓名", 33, "乙的新电话", "乙的新备注");
        assertThatThrownBy(() -> playerService.update(b.getId(), req, b.getVersion()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("球员编号已存在");

        Player storedB = playerRepository.findById(b.getId()).orElseThrow();
        assertThat(storedB.getPlayerNo()).isEqualTo("P-D2");
        assertThat(storedB.getName()).isEqualTo("乙球员");
        assertThat(storedB.getHandicap()).isEqualTo(12);
        assertThat(storedB.getPhone()).isEqualTo("13800000000");
        assertThat(storedB.getNote()).isEqualTo("老备注");
        assertThat(storedB.getVersion()).isEqualTo(b.getVersion());
        // 被撞编号的另一球员也没有被波及
        assertThat(playerRepository.findById(a.getId()).orElseThrow().getName()).isEqualTo("甲球员");
    }

    // ---------------- 4. 持久化异常也要整笔恢复：不能留下半更新状态 ----------------

    @Test
    void persistenceFailure_wholeEditRolledBack() {
        Player p = mkPlayer("P-E1", "周文博");

        doThrow(new RuntimeException("模拟数据库写入失败"))
                .when(spyPlayerRepository).save(any(Player.class));

        Player req = input("P-E1", "周文博改名", 50, "新电话", "新备注");
        assertThatThrownBy(() -> playerService.update(p.getId(), req, p.getVersion()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模拟数据库写入失败");

        Mockito.reset(spyPlayerRepository);
        Player stored = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("周文博");
        assertThat(stored.getPhone()).isEqualTo("13800000000");
        assertThat(stored.getNote()).isEqualTo("老备注");
        assertThat(stored.getHandicap()).isEqualTo(12);
        assertThat(stored.getVersion()).isEqualTo(p.getVersion());
    }

    // ---------------- 5. 合法修改：统一 trim、版本自增，列表 / 详情 / 赛事名单一起显示新资料 ----------------

    @Test
    void validEdit_trimsAndPropagatesEverywhere_versionIncrements() {
        Player p = mkPlayer("P-U1", "赵子涵");
        long v = p.getVersion();

        Course c = new Course();
        c.setCourseNo("C-PE"); c.setName("测试球场"); c.setHoleCount(3); c.setCreatedAt(LocalDateTime.now());
        courseRepository.save(c);
        Tournament t = new Tournament();
        t.setTournamentNo("T-PE"); t.setName("编辑后联展赛事");
        t.setStartDate(LocalDate.of(2026, 11, 1));
        t.setEndDate(LocalDate.of(2026, 11, 2));
        t.setStatus("报名中"); t.setLocation("测试场"); t.setCourse(c);
        t.setCreatedAt(LocalDateTime.now());
        tournamentRepository.save(t);
        TournamentEntry entry = new TournamentEntry();
        entry.setTournament(t); entry.setPlayer(p); entry.setRoundCount(1);
        entry.setCreatedAt(LocalDateTime.now());
        entryRepository.save(entry);

        Player req = input("  P-U1  ", "  赵子涵(新)  ", 9, "  137-1111  ", "  新备注  ");
        Player saved = playerService.update(p.getId(), req, v);

        assertThat(saved.getVersion()).isEqualTo(v + 1);
        assertThat(saved.getName()).isEqualTo("赵子涵(新)");
        assertThat(saved.getPlayerNo()).isEqualTo("P-U1");
        assertThat(saved.getPhone()).isEqualTo("137-1111");
        assertThat(saved.getNote()).isEqualTo("新备注");

        // 列表
        assertThat(playerService.list()).filteredOn(x -> x.getId().equals(p.getId())).singleElement()
                .satisfies(x -> {
                    assertThat(x.getName()).isEqualTo("赵子涵(新)");
                    assertThat(x.getVersion()).isEqualTo(v + 1);
                });
        // 球员详情 / 差点详情
        assertThat(playerService.get(p.getId()).getName()).isEqualTo("赵子涵(新)");
        assertThat(handicapService.playerView(p.getId()).playerName()).isEqualTo("赵子涵(新)");
        // 赛事相关显示（参赛名单通过 player 关联实时取名）
        TournamentEntry storedEntry = entryService.list(t.getId()).get(0);
        assertThat(storedEntry.getPlayerName()).isEqualTo("赵子涵(新)");
        assertThat(storedEntry.getPlayerNo()).isEqualTo("P-U1");
    }

    // ---------------- 6. 可选字段纯空白归一化为空（未填写），必填 trim 后生效 ----------------

    @Test
    void blankOptionalFields_normalizedToNull() {
        Player p = mkPlayer("P-U2", "郑凯");
        Player req = input("P-U2", "郑凯", 12, "   ", "  ");
        Player saved = playerService.update(p.getId(), req, p.getVersion());
        assertThat(saved.getPhone()).isNull();
        assertThat(saved.getNote()).isNull();
        assertThat(saved.getName()).isEqualTo("郑凯");
    }

    // ---------------- 7. 过期版本：ConflictException(409) 带当前球员，本次修改不生效 ----------------

    @Test
    void staleVersion_conflictCarriesCurrentPlayer_editRejected() {
        Player p = mkPlayer("P-C1", "黄诗韵");
        long stale = p.getVersion();

        // 另一管理员先基于最新版本改了电话，版本自增
        Player first = playerService.update(p.getId(),
                input("P-C1", "黄诗韵", 12, "先到者电话", null), p.getVersion());
        assertThat(first.getVersion()).isEqualTo(stale + 1);

        // 后到者拿着打开页面时的旧版本改姓名 → 409，data 为当前最新球员
        Player late = input("P-C1", "后到者姓名", 12, "后到者电话", null);
        assertThatThrownBy(() -> playerService.update(p.getId(), late, stale))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getMessage()).contains("数据已变化");
                    assertThat(ex.getMessage()).contains("v" + (stale + 1));
                    assertThat(ex.getData()).isInstanceOf(Player.class);
                    Player current = (Player) ex.getData();
                    assertThat(current.getVersion()).isEqualTo(stale + 1);
                    assertThat(current.getPhone()).isEqualTo("先到者电话");
                    assertThat(current.getName()).isEqualTo("黄诗韵");
                });

        // 先到者结果保留，后到者没有覆盖
        Player stored = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("黄诗韵");
        assertThat(stored.getPhone()).isEqualTo("先到者电话");
        assertThat(stored.getVersion()).isEqualTo(stale + 1);
    }

    // ---------------- 8. 并发编辑：只有一人成功，其余冲突或乐观锁失败，终态只来自唯一胜者 ----------------

    @Test
    void concurrentEdits_onlyOneWins_losersRejected() throws Exception {
        Player p = mkPlayer("P-C2", "吴晓彤");
        long v = p.getVersion();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    playerService.update(p.getId(),
                            input("P-C2", "并发改名-" + idx, 10 + idx, "电话-" + idx, "备注-" + idx), v);
                    success.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ConflictException | ObjectOptimisticLockingFailureException expected) {
                    rejected.incrementAndGet();
                } catch (RuntimeException re) {
                    unexpected.add(re);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(unexpected).as("unexpected: %s", unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        Player stored = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(v + 1);
        assertThat(stored.getName()).startsWith("并发改名-");
        assertThat(stored.getPhone()).startsWith("电话-");
        assertThat(stored.getNote()).startsWith("备注-");
        // 终态自洽：姓名 / 电话 / 备注来自同一个胜者，没有半截拼接
        String winnerIdx = stored.getName().substring("并发改名-".length());
        assertThat(stored.getPhone()).isEqualTo("电话-" + winnerIdx);
        assertThat(stored.getNote()).isEqualTo("备注-" + winnerIdx);
    }

    // ---------------- 9. HTTP 链路：空姓名 400 且资料不变；过期版本 409 且 data 带当前球员 ----------------

    @Test
    void http_blankName400AndStaleVersion409() throws Exception {
        Player p = mkPlayer("P-H1", "刘振华");
        var jsonHeaders = new org.springframework.http.HttpHeaders();
        jsonHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // 9.1 空姓名：400 + ok:false + 明确错误，不允许返回成功
        ResponseEntity<String> bad = rest.getRestTemplate().exchange(
                "/api/players/" + p.getId() + "?expectedVersion=" + p.getVersion(),
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(objectMapper.writeValueAsString(
                        input("P-H1", "   ", 12, null, null)), jsonHeaders),
                String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode badBody = objectMapper.readTree(bad.getBody());
        assertThat(badBody.path("ok").asBoolean()).isFalse();
        assertThat(badBody.path("message").asText()).contains("球员姓名不能为空");
        assertThat(playerRepository.findById(p.getId()).orElseThrow().getName()).isEqualTo("刘振华");

        // 另一管理员先改成功
        Player moved = playerService.update(p.getId(),
                input("P-H1", "刘振华-最新", 15, "最新电话", "最新备注"), p.getVersion());

        // 9.2 旧版本再提交：409，data 直接带回当前最新球员资料
        ResponseEntity<String> conflict = rest.getRestTemplate().exchange(
                "/api/players/" + p.getId() + "?expectedVersion=" + p.getVersion(),
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(objectMapper.writeValueAsString(
                        input("P-H1", "页面旧姓名", 1, "旧电话", null)), jsonHeaders),
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(conflict.getBody());
        assertThat(body.path("ok").asBoolean()).isFalse();
        assertThat(body.path("message").asText()).contains("数据已变化");
        assertThat(body.path("data").path("version").asLong()).isEqualTo(moved.getVersion());
        assertThat(body.path("data").path("name").asText()).isEqualTo("刘振华-最新");
        assertThat(body.path("data").path("phone").asText()).isEqualTo("最新电话");

        // 过期提交未写入
        Player stored = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("刘振华-最新");
        assertThat(stored.getVersion()).isEqualTo(moved.getVersion());

        // 9.3 带最新版本重试成功
        ResponseEntity<String> ok = rest.getRestTemplate().exchange(
                "/api/players/" + p.getId() + "?expectedVersion=" + moved.getVersion(),
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(objectMapper.writeValueAsString(
                        input("P-H1", "  刘振华-最终  ", 15, "  最新电话  ", "最新备注")), jsonHeaders),
                String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode okBody = objectMapper.readTree(ok.getBody());
        assertThat(okBody.path("ok").asBoolean()).isTrue();
        assertThat(okBody.path("data").path("name").asText()).isEqualTo("刘振华-最终");
        assertThat(okBody.path("data").path("phone").asText()).isEqualTo("最新电话");
    }
}
