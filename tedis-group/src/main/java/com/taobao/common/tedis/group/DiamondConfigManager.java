/**
 * (C) 2011-2012 Alibaba Group Holding Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 *
 */
package com.taobao.common.tedis.group;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.common.tedis.config.ConfigManager;
import com.taobao.common.tedis.config.HAConfig;
import com.taobao.common.tedis.config.HAConfig.ServerInfo;
import com.taobao.common.tedis.config.HAConfig.ServerProperties;
import com.taobao.common.tedis.config.Router;
import com.taobao.diamond.manager.DiamondManager;
import com.taobao.diamond.manager.ManagerListener;
import com.taobao.diamond.manager.impl.DefaultDiamondManager;

/**
 * HA配置实现。
 *
 * @author jianxing <jianxing.qx@taobao.com>
 */
public class DiamondConfigManager implements ConfigManager {

    static Log logger = LogFactory.getLog(DiamondConfigManager.class);
    public static String defaultConfKey = "com.taobao.common.tedis.config";
    volatile Router router;
    DiamondManager diamondManager;
    String configKey;
    int timeout = 3000;
    // 保存所有的配置
    public volatile HAConfig haConfig;
    Executor e = Executors.newSingleThreadExecutor();
    public ManagerListenerImpl managerListener = new ManagerListenerImpl();
    String appName;
    String version;

    public DiamondConfigManager(String appName, String version) {
        init(appName, version);
    }

    private void init(String appName, String version) {
        this.appName = appName;
        this.version = version;
        this.configKey = defaultConfKey + "." + appName + "." + version;
        diamondManager = new DefaultDiamondManager(configKey, (ManagerListener) null);
        // ip:port:r10,ip:port:r10
        String configString = diamondManager.getConfigureInfomation(timeout);
        this.haConfig = parseConfig(configString);
        if (this.haConfig.password != null) {
            for (ServerProperties sp : haConfig.groups) {
                sp.password = this.haConfig.password;
            }
        }
        if (haConfig.ms) {
            this.router = new MSRandomRouter(haConfig.groups, haConfig.failover);
        } else {
            this.router = new RandomRouter(haConfig.groups, haConfig.failover);
        }
        diamondManager.setManagerListener(managerListener);
    }

    @Override
    public Router getRouter() {
        return router;
    }

    public void setDefaultConfigKey(String key) {
        this.configKey = key;
    }

    @Override
    public void destroy() {
        diamondManager.close();
    }

    public static HAConfig parseConfig(String configString) {
        HAConfig config = new HAConfig();
        // timeout
        Pattern p_timeout = Pattern.compile("timeout=([\\s\\S]+?);");
        Matcher m_timeout = p_timeout.matcher(configString);
        if (m_timeout.find()) {
            String s_timeout = m_timeout.group(1);
            logger.info("timeout=" + s_timeout);
            try {
                config.timeout = Integer.parseInt(s_timeout.trim());
            } catch (Exception ex) {
                logger.error("timeout解析错误:", ex);
            }
        }
        // pool_size
        Pattern p_pool_size = Pattern.compile("pool_size=([\\s\\S]+?);");
        Matcher m_pool_size = p_pool_size.matcher(configString);
        if (m_pool_size.find()) {
            String s_pool_size = m_pool_size.group(1);
            logger.info("pool_size=" + s_pool_size);
            try {
                config.pool_size = Integer.parseInt(s_pool_size.trim());
            } catch (Exception ex) {
                logger.error("pool_size解析错误:", ex);
            }
        }

        // password
        Pattern p_password = Pattern.compile("password=([\\s\\S]+?);");
        Matcher m_password = p_password.matcher(configString);
        if (m_password.find()) {
            String s_password = m_password.group(1);
            logger.info("password=" + s_password);
            try {
                config.password = s_password.trim();
            } catch (Exception ex) {
                logger.error("password解析错误:", ex);
            }
        }

        // servers
        Pattern p = Pattern.compile("servers=([\\s\\S]+?);", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(configString);
        if (m.find()) {
            String s_servers = m.group(1);
            logger.info("servers=" + s_servers);
            String[] array = s_servers.trim().split(",");
            List<ServerProperties> servers = new ArrayList<ServerProperties>();
            for (String s : array) {
                ServerProperties sp = new ServerProperties();
                sp.server = new ServerInfo();
                String[] ss = s.split(":");
                if (ss.length >= 2) {
                    sp.server.addr = ss[0];
                    sp.server.port = Integer.parseInt(ss[1]);
                    sp.pool_size = config.pool_size;
                    sp.timeout = config.timeout;
                    sp.password = config.password;
                    if (ss.length == 3) {
                        sp.readWeight = Integer.parseInt(ss[2].toLowerCase().replace("r", "").trim());
                    }
                } else {
                    logger.error("配置错误:" + s);
                }
                servers.add(sp);
            }
            config.groups = servers;
        } else {
            logger.error("servers配置解析不到:" + configString);
        }
        // fail over开关
        Pattern p_failover = Pattern.compile("failover=([\\s\\S]+?);", Pattern.CASE_INSENSITIVE);
        Matcher m_failover = p_failover.matcher(configString);
        if (m_failover.find()) {
            try {
                String s_failover = m_failover.group(1);
                logger.info("failover=" + s_failover);
                config.failover = Boolean.parseBoolean(s_failover.trim());
            } catch (Throwable t) {
                logger.error("failover开关解析出错", t);
            }
        }

        // 是否支持master slave结构
        Pattern p_ms = Pattern.compile("ms=([\\s\\S]+?);", Pattern.CASE_INSENSITIVE);
        Matcher m_ms = p_ms.matcher(configString);
        if (m_ms.find()) {
            try {
                String s_ms = m_ms.group(1);
                logger.info("ms=" + s_ms);
                config.ms = Boolean.parseBoolean(s_ms.trim());
            } catch (Throwable t) {
                logger.error("master slave配置解析出错", t);
            }
        }
        return config;
    }

    public class ManagerListenerImpl implements ManagerListener {

        @Override
        public Executor getExecutor() {
            return e;
        }

        @Override
        public void receiveConfigInfo(String string) {
            logger.warn("配置变更：" + string);
            haConfig = parseConfig(string);
            Router old = router;
            if (haConfig.ms) {
                router = new MSRandomRouter(haConfig.groups, haConfig.failover);
            } else {
                router = new RandomRouter(haConfig.groups, haConfig.failover);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                logger.warn("InterruptedException:", ex);
            }
            old.destroy();
        }
    }

}
