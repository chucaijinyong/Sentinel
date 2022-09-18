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
package com.alibaba.csp.sentinel.command;

import com.alibaba.csp.sentinel.command.annotation.CommandMapping;
import com.alibaba.csp.sentinel.spi.SpiLoader;
import com.alibaba.csp.sentinel.util.StringUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides and filters command handlers registered via SPI.
 *
 * @author Eric Zhao
 */
public class CommandHandlerProvider implements Iterable<CommandHandler> {

    private final SpiLoader<CommandHandler> spiLoader = SpiLoader.of(CommandHandler.class);

    /**
     * Get all command handlers annotated with {@link CommandMapping} with command name.
     * @return list of all named command handlers
     * 通过spi机制加载所有实现了CommandHandler的类，将其封装为处理器集合
     */
    public Map<String, CommandHandler> namedHandlers() {
        Map<String, CommandHandler> map = new HashMap<String, CommandHandler>();
        // 加载命令处理器实例
        List<CommandHandler> handlers = spiLoader.loadInstanceList();
        // 将命令处理器实例转换为命令处理器映射
        for (CommandHandler handler : handlers) {
            String name = parseCommandName(handler);
            if (!StringUtil.isEmpty(name)) {
                map.put(name, handler);
            }
        }
        return map;
    }

    private String parseCommandName(CommandHandler handler) {
        CommandMapping commandMapping = handler.getClass().getAnnotation(CommandMapping.class);
        if (commandMapping != null) {
            return commandMapping.name();
        } else {
            return null;
        }
    }

    @Override
    public Iterator<CommandHandler> iterator() {
        return spiLoader.loadInstanceList().iterator();
    }

    private static final CommandHandlerProvider INSTANCE = new CommandHandlerProvider();

    public static CommandHandlerProvider getInstance() {
        return INSTANCE;
    }
}
