package com.larder.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.larder.content.model.Owner;
import com.larder.content.model.OwnerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 单管理员账户解析：username → owner_id。
 *
 * <p>V01 的认证是内存用户，owner 表是业务归属权威（item/job 的外键指向它）。
 * 首次请求时按需创建 owner 行；并发下靠 uk_owner_username 唯一键兜底，冲突后回读。
 *
 * <p>password_hash 暂时是占位值（"!in-memory-auth!"）：V02 起认证仍走 V01 的实现，
 * 把密码迁进 DB 需要单独任务（涉及 session 存储与迁移），不在本卡偷做。
 */
@Service
public class OwnerService {

    static final String PASSWORD_PLACEHOLDER = "!in-memory-auth!";

    private final OwnerMapper ownerMapper;

    public OwnerService(OwnerMapper ownerMapper) {
        this.ownerMapper = ownerMapper;
    }

    // REQUIRES_NEW：必须在主事务开始前提交 owner 行。否则 idempotency_request 的
    // 外键校验会等待主事务未提交的 owner 行，形成锁等待（V02-T01 实测的 504）。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String ownerIdFor(String username) {
        Owner found = selectByUsername(username);
        if (found != null) {
            return found.getId();
        }
        Owner created = new Owner();
        created.setId(Ids.next("own"));
        created.setUsername(username);
        created.setPasswordHash(PASSWORD_PLACEHOLDER);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            ownerMapper.insert(created);
            return created.getId();
        } catch (DuplicateKeyException race) {
            Owner winner = selectByUsername(username);
            if (winner == null) {
                throw race;
            }
            return winner.getId();
        }
    }

    private Owner selectByUsername(String username) {
        return ownerMapper.selectOne(Wrappers.<Owner>lambdaQuery().eq(Owner::getUsername, username));
    }
}
