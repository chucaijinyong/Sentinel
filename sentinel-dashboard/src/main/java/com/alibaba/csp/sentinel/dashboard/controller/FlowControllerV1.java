/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.controller;

import com.alibaba.csp.sentinel.dashboard.auth.AuthAction;
import com.alibaba.csp.sentinel.dashboard.auth.AuthService.PrivilegeType;
import com.alibaba.csp.sentinel.dashboard.client.SentinelApiClient;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.FlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.discovery.MachineInfo;
import com.alibaba.csp.sentinel.dashboard.domain.Result;
import com.alibaba.csp.sentinel.dashboard.repository.rule.InMemoryRuleRepositoryAdapter;
import com.alibaba.csp.sentinel.dashboard.rule.DynamicRuleProvider;
import com.alibaba.csp.sentinel.dashboard.rule.DynamicRulePublisher;
import com.alibaba.csp.sentinel.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Flow rule controller.
 * @author leyou
 * @author Eric Zhao
 */
@RestController
@RequestMapping(value = "/v1/flow")
public class FlowControllerV1 {

    private final Logger logger = LoggerFactory.getLogger(FlowControllerV1.class);

    @Autowired
    private InMemoryRuleRepositoryAdapter<FlowRuleEntity> repository;

    @Autowired
    private SentinelApiClient sentinelApiClient;

    @Autowired
    @Qualifier("flowRuleNacosProvider")
    private DynamicRuleProvider<List<FlowRuleEntity>> ruleProvider;

    @Autowired
    @Qualifier("flowRuleNacosPublisher")
    private DynamicRulePublisher<List<FlowRuleEntity>> rulePublisher;

    /**
     * 查询流控规则
     */
    @GetMapping("/rules")
    @AuthAction(PrivilegeType.READ_RULE)
    public Result<List<FlowRuleEntity>> apiQueryMachineRules(@RequestParam String app,
                                                             @RequestParam String ip,
                                                             @RequestParam Integer port) {

        if (StringUtil.isEmpty(app)) {
            return Result.ofFail(-1, "app can't be null or empty");
        }
        if (StringUtil.isEmpty(ip)) {
            return Result.ofFail(-1, "ip can't be null or empty");
        }
        if (port == null) {
            return Result.ofFail(-1, "port can't be null");
        }
        try {
            // 拿到客户端对应的节点的数据，将数据放到内存中，会远程调用com.alibaba.csp.sentinel.command.handler.FetchActiveRuleCommandHandler.handle
            //  如果是拉模式要放开此处
            // List<FlowRuleEntity> rules = sentinelApiClient.fetchFlowRuleOfMachine(app, ip, port);
            //不从缓存中获取了，而是直接从nacos配置中心获取规则配置，这样的好处就是多个微服务的规则配置可以进行统一的管理。当nacos有变更也能通过nacos的监听器
            // 通知sentinel的监听器，这就是push模式
            List<FlowRuleEntity> rules = ruleProvider.getRules(app,ip,port);
            if (rules != null && !rules.isEmpty()) {
                for (FlowRuleEntity entity : rules) {
                    entity.setApp(app);
                    if (entity.getClusterConfig() != null && entity.getClusterConfig().getFlowId() != null) {
                        entity.setId(entity.getClusterConfig().getFlowId());
                    }
                }
            }
            rules = repository.saveAll(rules);
            return Result.ofSuccess(rules);
        } catch (Throwable throwable) {
            logger.error("Error when querying flow rules", throwable);
            return Result.ofThrowable(-1, throwable);
        }
    }

    private <R> Result<R> checkEntityInternal(FlowRuleEntity entity) {
        if (StringUtil.isBlank(entity.getApp())) {
            return Result.ofFail(-1, "app can't be null or empty");
        }
        if (StringUtil.isBlank(entity.getIp())) {
            return Result.ofFail(-1, "ip can't be null or empty");
        }
        if (entity.getPort() == null) {
            return Result.ofFail(-1, "port can't be null");
        }
        if (StringUtil.isBlank(entity.getLimitApp())) {
            return Result.ofFail(-1, "limitApp can't be null or empty");
        }
        if (StringUtil.isBlank(entity.getResource())) {
            return Result.ofFail(-1, "resource can't be null or empty");
        }
        if (entity.getGrade() == null) {
            return Result.ofFail(-1, "grade can't be null");
        }
        if (entity.getGrade() != 0 && entity.getGrade() != 1) {
            return Result.ofFail(-1, "grade must be 0 or 1, but " + entity.getGrade() + " got");
        }
        if (entity.getCount() == null || entity.getCount() < 0) {
            return Result.ofFail(-1, "count should be at lease zero");
        }
        if (entity.getStrategy() == null) {
            return Result.ofFail(-1, "strategy can't be null");
        }
        if (entity.getStrategy() != 0 && StringUtil.isBlank(entity.getRefResource())) {
            return Result.ofFail(-1, "refResource can't be null or empty when strategy!=0");
        }
        if (entity.getControlBehavior() == null) {
            return Result.ofFail(-1, "controlBehavior can't be null");
        }
        int controlBehavior = entity.getControlBehavior();
        if (controlBehavior == 1 && entity.getWarmUpPeriodSec() == null) {
            return Result.ofFail(-1, "warmUpPeriodSec can't be null when controlBehavior==1");
        }
        if (controlBehavior == 2 && entity.getMaxQueueingTimeMs() == null) {
            return Result.ofFail(-1, "maxQueueingTimeMs can't be null when controlBehavior==2");
        }
        if (entity.isClusterMode() && entity.getClusterConfig() == null) {
            return Result.ofFail(-1, "cluster config should be valid");
        }
        return null;
    }

    /**
     * 增加流控规则
     */
    @PostMapping("/rule")
    @AuthAction(PrivilegeType.WRITE_RULE)
    public Result<FlowRuleEntity> apiAddFlowRule(@RequestBody FlowRuleEntity entity) {
        Result<FlowRuleEntity> checkResult = checkEntityInternal(entity);
        if (checkResult != null) {
            return checkResult;
        }
        entity.setId(null);
        Date date = new Date();
        entity.setGmtCreate(date);
        entity.setGmtModified(date);
        entity.setLimitApp(entity.getLimitApp().trim());
        entity.setResource(entity.getResource().trim());
        try {
            // 在此地方可以进行扩展，比如存储到redis或者mysql,或者file等数据库
            entity = repository.save(entity);
            // 此时会发送一个get或者post请求,异步和sentinel的客户端进行通信
            //  如果是拉模式要放开此处，所谓拉模式就是当配置文件有变更，sentinel的写数据源会每隔3秒去比较是否变更，变更了就去执行内存更新逻辑
            // publishRules(entity.getApp(), entity.getIp(), entity.getPort()).get(5000, TimeUnit.MILLISECONDS);
            //发布规则到nacos配置中心，推模式
            publishRules(entity.getApp());
            return Result.ofSuccess(entity);
        } catch (Throwable t) {
            Throwable e = t instanceof ExecutionException ? t.getCause() : t;
            logger.error("Failed to add new flow rule, app={}, ip={}", entity.getApp(), entity.getIp(), e);
            return Result.ofFail(-1, e.getMessage());
        }
    }

    /**
     * 更新流控规则
     */
    @PutMapping("/save.json")
    @AuthAction(PrivilegeType.WRITE_RULE)
    public Result<FlowRuleEntity> apiUpdateFlowRule(Long id, String app,
                                                    String limitApp, String resource, Integer grade,
                                                    Double count, Integer strategy, String refResource,
                                                    Integer controlBehavior, Integer warmUpPeriodSec,
                                                    Integer maxQueueingTimeMs) {
        if (id == null) {
            return Result.ofFail(-1, "id can't be null");
        }
        FlowRuleEntity entity = repository.findById(id);
        if (entity == null) {
            return Result.ofFail(-1, "id " + id + " dose not exist");
        }
        if (StringUtil.isNotBlank(app)) {
            entity.setApp(app.trim());
        }
        if (StringUtil.isNotBlank(limitApp)) {
            entity.setLimitApp(limitApp.trim());
        }
        if (StringUtil.isNotBlank(resource)) {
            entity.setResource(resource.trim());
        }
        if (grade != null) {
            if (grade != 0 && grade != 1) {
                return Result.ofFail(-1, "grade must be 0 or 1, but " + grade + " got");
            }
            entity.setGrade(grade);
        }
        if (count != null) {
            entity.setCount(count);
        }
        if (strategy != null) {
            if (strategy != 0 && strategy != 1 && strategy != 2) {
                return Result.ofFail(-1, "strategy must be in [0, 1, 2], but " + strategy + " got");
            }
            entity.setStrategy(strategy);
            if (strategy != 0) {
                if (StringUtil.isBlank(refResource)) {
                    return Result.ofFail(-1, "refResource can't be null or empty when strategy!=0");
                }
                entity.setRefResource(refResource.trim());
            }
        }
        if (controlBehavior != null) {
            if (controlBehavior != 0 && controlBehavior != 1 && controlBehavior != 2) {
                return Result.ofFail(-1, "controlBehavior must be in [0, 1, 2], but " + controlBehavior + " got");
            }
            if (controlBehavior == 1 && warmUpPeriodSec == null) {
                return Result.ofFail(-1, "warmUpPeriodSec can't be null when controlBehavior==1");
            }
            if (controlBehavior == 2 && maxQueueingTimeMs == null) {
                return Result.ofFail(-1, "maxQueueingTimeMs can't be null when controlBehavior==2");
            }
            entity.setControlBehavior(controlBehavior);
            if (warmUpPeriodSec != null) {
                entity.setWarmUpPeriodSec(warmUpPeriodSec);
            }
            if (maxQueueingTimeMs != null) {
                entity.setMaxQueueingTimeMs(maxQueueingTimeMs);
            }
        }
        Date date = new Date();
        entity.setGmtModified(date);
        try {
            entity = repository.save(entity);

            if (entity == null) {
                return Result.ofFail(-1, "save entity fail: null");
            }
            // 这个地方是拓展点，在发布规则时可以替换成直接往配置中心进行持久化
            // publishRules(entity.getApp(), entity.getIp(), entity.getPort()).get(5000, TimeUnit.MILLISECONDS);
            //发布规则到配置中心
            publishRules(entity.getApp());
            return Result.ofSuccess(entity);
        } catch (Throwable t) {
            Throwable e = t instanceof ExecutionException ? t.getCause() : t;
            logger.error("Error when updating flow rules, app={}, ip={}, ruleId={}", entity.getApp(),
                    entity.getIp(), id, e);
            return Result.ofFail(-1, e.getMessage());
        }
    }

    /**
     * 删除流控规则
     */
    @DeleteMapping("/delete.json")
    @AuthAction(PrivilegeType.WRITE_RULE)
    public Result<Long> apiDeleteFlowRule(Long id) {

        if (id == null) {
            return Result.ofFail(-1, "id can't be null");
        }
        FlowRuleEntity oldEntity = repository.findById(id);
        if (oldEntity == null) {
            return Result.ofSuccess(null);
        }

        try {
            repository.delete(id);
        } catch (Exception e) {
            return Result.ofFail(-1, e.getMessage());
        }
        try {
            // publishRules(oldEntity.getApp(), oldEntity.getIp(), oldEntity.getPort()).get(5000, TimeUnit
            // .MILLISECONDS);
            //发布规则到配置中心
            publishRules(oldEntity.getApp());
            return Result.ofSuccess(id);
        } catch (Throwable t) {
            Throwable e = t instanceof ExecutionException ? t.getCause() : t;
            logger.error("Error when deleting flow rules, app={}, ip={}, id={}", oldEntity.getApp(),
                    oldEntity.getIp(), id, e);
            return Result.ofFail(-1, e.getMessage());
        }
    }

    /**
     * 界面变动更新规则到sentinel客户端的内存，但是客户端重启后规则就丢失了
     */
    private CompletableFuture<Void> publishRules(String app, String ip, Integer port) {
        List<FlowRuleEntity> rules = repository.findAllByMachine(MachineInfo.of(app, ip, port));
        // 异步发送请求
        return sentinelApiClient.setFlowRuleOfMachineAsync(app, ip, port, rules);
    }

    /**
     * 发布规则到配置中心
     * @param app
     * @exception Exception
     */
    private void publishRules(/*@NonNull*/ String app) throws Exception {
        List<FlowRuleEntity> rules = repository.findAllByApp(app);
        rulePublisher.publish(app, rules);
    }
}
