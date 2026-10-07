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
 * 球员业务。核心规则：① 球员编号唯一；② 差点 handicap 必须落在 0 ~ 54 之间；
 * ③ 姓名、编号为必填，服务端统一去除首尾空格后校验非空——空串 / 纯空格一律明确拒绝，
 * 绝不允许「不进入更新分支却返回成功」；④ 编辑按 {@link Player#getVersion()} 乐观锁，
 * 多人同时维护同一球员时只有基于最新版本的一次提交生效。
 *
 * <p>更新采用「整份资料提交、先全部校验后落库」：任何一项校验失败或持久化异常，
 * 整个事务回滚，姓名 / 电话 / 备注不会出现只更新一半的中间状态。</p>
 */
@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Transactional(readOnly = true)
    public List<Player> list() {
        return playerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Player get(Long id) {
        return playerRepository.findById(id).orElseThrow(() -> new BizException("球员不存在：" + id));
    }

    @Transactional
    public Player create(Player input) {
        String playerNo = requireTrimmed(input.getPlayerNo(), "球员编号", 16);
        String name = requireTrimmed(input.getName(), "球员姓名", 48);
        Integer handicap = input.getHandicap();
        if (!Dict.isValidHandicap(handicap)) {
            throw new BizException("差点须落在 0 ~ 54 之间，当前：" + handicap);
        }
        // 全部参数先校验完，再触碰持久层 / 新建实体：编号重复等失败不会留下任何半成品
        playerRepository.findByPlayerNo(playerNo).ifPresent(p -> {
            throw new BizException("球员编号已存在：" + playerNo);
        });

        Player player = new Player();
        player.setPlayerNo(playerNo);
        player.setName(name);
        player.setHandicap(handicap);
        player.setPhone(optionalTrimmed(input.getPhone(), 24, "联系电话"));
        player.setNote(optionalTrimmed(input.getNote(), 64, "备注"));
        player.setCreatedAt(LocalDateTime.now());
        return playerRepository.save(player);
    }

    /**
     * 编辑球员（整份资料提交）。
     *
     * <p>姓名、编号必填（去首尾空格后非空）；电话 / 备注可空，纯空白按「未填写」处理。
     * 所有字段先统一清洗并校验通过后才写入实体，配合事务保证：任一校验或持久化失败，
     * 姓名、电话、备注整体恢复为编辑前资料，不会留下只更新一部分的状态。</p>
     *
     * @param expectedVersion 页面打开该球员时读到的版本号；与当前版本不一致说明资料已被他人改动，
     *                        抛 {@link ConflictException}（409）并在 data 中带回当前最新球员，
     *                        本次编辑不生效，页面须提示「数据已变化」并重新读取。
     */
    @Transactional
    public Player update(Long id, Player input, Long expectedVersion) {
        Player player = get(id);
        if (expectedVersion != null && !expectedVersion.equals(player.getVersion())) {
            throw new ConflictException("球员数据已变化：你打开的是 v" + expectedVersion
                    + "，当前已是 v" + player.getVersion()
                    + "。本次修改未生效，请重新读取当前球员资料后再编辑", player);
        }

        // 先清洗 + 校验全部输入，任何失败都在写入实体之前抛出，事务回滚、原资料完整保留
        String playerNo = requireTrimmed(input.getPlayerNo(), "球员编号", 16);
        String name = requireTrimmed(input.getName(), "球员姓名", 48);
        if (!Dict.isValidHandicap(input.getHandicap())) {
            throw new BizException("差点须落在 0 ~ 54 之间，当前：" + input.getHandicap());
        }
        String phone = optionalTrimmed(input.getPhone(), 24, "联系电话");
        String note = optionalTrimmed(input.getNote(), 64, "备注");
        playerRepository.findByPlayerNo(playerNo).ifPresent(p -> {
            if (!p.getId().equals(id)) {
                throw new BizException("球员编号已存在：" + playerNo);
            }
        });

        player.setPlayerNo(playerNo);
        player.setName(name);
        player.setHandicap(input.getHandicap());
        player.setPhone(phone);
        player.setNote(note);
        return playerRepository.save(player);
    }

    /** 必填文本：去除首尾空格后不允许为空 / 纯空白，并限制入库长度 */
    private String requireTrimmed(String raw, String label, int maxLength) {
        String v = raw == null ? null : raw.trim();
        if (v == null || v.isEmpty()) {
            throw new BizException(label + "不能为空（不允许只填空格）");
        }
        if (v.length() > maxLength) {
            throw new BizException(label + "长度不能超过 " + maxLength + " 个字符，当前：" + v.length());
        }
        return v;
    }

    /** 可选文本：统一去除首尾空格；空白归一化为 null（表示未填写），超长明确拒绝 */
    private String optionalTrimmed(String raw, int maxLength, String label) {
        String v = raw == null ? null : raw.trim();
        if (v != null && v.isEmpty()) {
            v = null;
        }
        if (v != null && v.length() > maxLength) {
            throw new BizException(label + "长度不能超过 " + maxLength + " 个字符，当前：" + v.length());
        }
        return v;
    }
}
