package io.dongtai.iast.core.handler.hookpoint.api;

import io.dongtai.iast.core.bytecode.enhance.plugin.spring.SpringApplicationImpl;
import io.dongtai.log.DongTaiLog;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public class GetApiThread extends Thread {

    private final Object applicationContext;

    public GetApiThread(Object applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run() {
        if (SpringApplicationImpl.getAPI == null) {
            return;
        }
        Map<String, Object> invoke = null;
        try {
            invoke = (Map<String, Object>) SpringApplicationImpl.getAPI.invoke(null, applicationContext);
            ApiReport.sendReport(invoke);
        } catch (IllegalAccessException e) {
            DongTaiLog.error("GetApiThread failed: " + e.toString(), e);
        } catch (InvocationTargetException e) {
            DongTaiLog.error("GetApiThread failed: " + e.toString(), e);
        } finally {
            SpringApplicationImpl.isSend = true;
        }
    }

}