package com.golf.course.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.golf.course.common.BizException;
import com.golf.course.common.ConflictException;
import com.golf.course.common.Dict;
import com.golf.course.entity.Player;
import com.golf.course.repository.PlayerRepository;

/**
 * 球员业务。核心规则：① 球员编号唯一；② 差点 handicap 必须落在 0 ~ 54 之间。
 *
 * <p>姓名、编号等必填资料一律在服务端先 {@code trim()} 再校验非空——
 * 空串或只含空格都按「必填资料缺失」拒绝（400），不允许静默跳过更新分支却返回成功；
 * 拒绝时实体还未被改动、事务回滚，库里保留原球员资料，形成可核对的空值拒绝结果。</p>
 *
 * <p>编辑携带 {@code expectedVersion} 乐观版本：多人同时编辑同一球员时，
 * 只有基于最新版本的一次提交生效，过期操作收到 409「数据已变化」并带回当前球员资料，
 * 页面必须重新读取后再编辑；JPA {@code @Version} 在提交时再兜一层。
 * 任何校验 / 持久化异常都让整笔事务回滚，姓名、电话、备注不会只更新一部分。</p>
 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public List<Player> list() {
        return playerRepository.findAll();
    }

    public Player get(Long id) {
        return playerRepository.findById(id).orElseThrow(() -> new BizException("球员不存在：" + id));
    }

    @Transactional
    public Player create(Player input) {
        String playerNo = requireText(input.getPlayerNo(), "球员编号");
        String name = requireText(input.getName(), "球员姓名");
        playerRepository.findByPlayerNo(playerNo).ifPresent(p -> {
            throw new BizException("球员编号已存在：" + playerNo);
        });
        if (!Dict.isValidHandicap(input.getHandicap())) {
            throw new BizException("差点须落在 0 ~ 54 之间，当前：" + input.getHandicap());
        }
        Player player = new Player();
        player.setPlayerNo(playerNo);
        player.setName(name);
        player.setHandicap(input.getHandicap());
        player.setPhone(optionalText(input.getPhone()));
        player.setNote(optionalText(input.getNote()));
        player.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(player);
    }

    /**
     * 编辑球员。必填资料（编号 / 姓名）服务端统一去空格并校验非空；
     * 所有校验先通过、再开始改实体，配合事务保证：任一校验或持久化失败，
     * 姓名 / 差点 / 电话 / 备注全部恢复为编辑前资料，不留半成品。
     */
    @Transactional
    public Player update(Long id, Player input, Long expectedVersion) {
        Player player = get(id);
        checkVersion(player, expectedVersion);

        // 先把本次入参全部归一化、校验完，任何一条不过都在动实体之前抛出，
        // 库里保持原资料不变（空值拒绝可核对：GET 回来仍是旧姓名 / 旧编号）。
        String playerNo = requireText(input.getPlayerNo(), "球员编号");
        String name = requireText(input.getName(), "球员姓名");
        if (!Dict.isValidHandicap(input.getHandicap())) {
            throw new BizException("差点须落在 0 ~ 54 之间，当前：" + input.getHandicap());
        }
        String phone = optionalText(input.getPhone());
        String note = optionalText(input.getNote());
        if (!playerNo.equals(player.getPlayerNo())) {
            playerRepository.findByPlayerNo(playerNo).ifPresent(p -> {
                throw new BizException("球员编号已存在：" + playerNo);
            });
        }

        player.setPlayerNo(playerNo);
        player.setName(name);
        player.setHandicap(input.getHandicap());
        player.setPhone(phone);
        player.setNote(note);
        return playerRepository.save(player);
    }

    /** 必填文本：去首尾空格后非空，否则按必填缺失明确拒绝。 */
    private String requireText(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(label + "不能为空（含纯空格），本次修改未保存，原资料已保留");
        }
        return raw.trim();
    }

    /** 可选文本：统一去首尾空格，去空后为空串则按 NULL 存储，避免只含空格的脏值。 */
    private String optionalText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * 球员乐观版本闸门：编辑必须基于最新球员版本。版本不一致直接 409，
     * 响应 data 带回当前球员资料，前端据此提示「数据已变化」并重新读取当前球员，
     * 杜绝两人同时编辑时后到者静默覆盖先到者。JPA {@code @Version} 在提交时还会再兜一层。
     */
    private void checkVersion(Player player, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(player.getVersion())) {
            throw new ConflictException("球员「" + player.getName() + "」的数据已变化：你打开的是 v"
                    + expectedVersion + "，当前已是 v" + player.getVersion()
                    + "。本次修改未生效，请重新读取当前球员资料后再编辑", player);
        }
    }
}
