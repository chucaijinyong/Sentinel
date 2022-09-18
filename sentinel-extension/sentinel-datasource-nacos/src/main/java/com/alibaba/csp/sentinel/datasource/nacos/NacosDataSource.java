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
package com.alibaba.csp.sentinel.datasource.nacos;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.datasource.AbstractDataSource;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;
import java.util.concurrent.*;

/**
 * A read-only {@code DataSource} with Nacos backend. When the data in Nacos backend has been modified,
 * Nacos will automatically push the new value so that the dynamic configuration can be real-time.
 *
 * @author Eric Zhao
 */
@Slf4j
public class NacosDataSource<T> extends AbstractDataSource<String, T> {

    private static final int DEFAULT_TIMEOUT = 3000;

    /**
     * Single-thread pool. Once the thread pool is blocked, we throw up the old task.
     */
    private final ExecutorService pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(1), new NamedThreadFactory("sentinel-nacos-ds-update", true),
        new ThreadPoolExecutor.DiscardOldestPolicy());

    private final Listener configListener;
    private final String groupId;
    private final String dataId;
    private final Properties properties;

    /**
     * Note: The Nacos config might be null if its initialization failed.
     */
    private ConfigService configService = null;

    /**
     * Constructs an read-only DataSource with Nacos backend.
     *
     * @param serverAddr server address of Nacos, cannot be empty
     * @param groupId    group ID, cannot be empty
     * @param dataId     data ID, cannot be empty
     * @param parser     customized data parser, cannot be empty
     */
    public NacosDataSource(final String serverAddr, final String groupId, final String dataId,
                           Converter<String, T> parser) {
        this(NacosDataSource.buildProperties(serverAddr), groupId, dataId, parser);
    }

    /**
     *
     * @param properties properties for construct {@link ConfigService} using {@link NacosFactory#createConfigService(Properties)}
     * @param groupId    group ID, cannot be empty
     * @param dataId     data ID, cannot be empty
     * @param parser     customized data parser, cannot be empty
     */
    public NacosDataSource(final Properties properties, final String groupId, final String dataId,
                           Converter<String, T> parser) {
        super(parser);
        if (StringUtil.isBlank(groupId) || StringUtil.isBlank(dataId)) {
            throw new IllegalArgumentException(String.format("Bad argument: groupId=[%s], dataId=[%s]",
                groupId, dataId));
        }
        AssertUtil.notNull(properties, "Nacos properties must not be null, you could put some keys from PropertyKeyConst");
        this.groupId = groupId;
        this.dataId = dataId;
        this.properties = properties;
        // 此时只是创建了一个configListener
        this.configListener = new Listener() {
            @Override
            public Executor getExecutor() {
                return pool;
            }
           // nacos的配置文件发生变化会触发nacos配置监听器的行为
            @Override
            public void receiveConfigInfo(final String configInfo) {
                RecordLog.info("[NacosDataSource] New property value received for (properties: {}) (dataId: {}, groupId: {}): {}",
                    properties, dataId, groupId, configInfo);
                log.info("[NacosDataSource] New property value received for (properties: {}) (dataId: {}, groupId: {}): {}",
                        properties, dataId, groupId, configInfo);
               // 解析变更的配置，将最新的配置更新到缓存
                T newValue = NacosDataSource.this.parser.convert(configInfo);
                log.info("newValue"+newValue);
                // Update the new value to the property.
                // 通过调用sentinel的属性监听器，从而完成配置的更新
                getProperty().updateValue(newValue);
            }
        };
       // 将nacos的配置文件和nacos的监听器进行绑定，一旦配置有变化就会执行nacos的监听器的业务逻辑
        initNacosListener();
       // 从nacos中加载最新配置到缓存
        loadInitialConfig();
    }

    /**
    * 从nacos中加载最新配置到缓存
    */
    private void loadInitialConfig() {
        try {
            T newValue = loadConfig();
            if (newValue == null) {
                RecordLog.warn("[NacosDataSource] WARN: initial config is null, you may have to check your data source");
            }
            getProperty().updateValue(newValue);
        } catch (Exception ex) {
            RecordLog.warn("[NacosDataSource] Error when loading initial config", ex);
        }
    }

    /**
    * 为配置增加监听器
    */
    private void initNacosListener() {
        try {
            this.configService = NacosFactory.createConfigService(this.properties);
            // Add config listener.
            configService.addListener(dataId, groupId, configListener);
        } catch (Exception e) {
            RecordLog.warn("[NacosDataSource] Error occurred when initializing Nacos data source", e);
            e.printStackTrace();
        }
    }

    /**
    * 通过dataId和groupId，从配置中心获取配置文件
    */
    @Override
    public String readSource() throws Exception {
        if (configService == null) {
            throw new IllegalStateException("Nacos config service has not been initialized or error occurred");
        }
        return configService.getConfig(dataId, groupId, DEFAULT_TIMEOUT);
    }

    @Override
    public void close() {
        if (configService != null) {
            configService.removeListener(dataId, groupId, configListener);
        }
        pool.shutdownNow();
    }

    private static Properties buildProperties(String serverAddr) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        return properties;
    }
}
