package io.dongtai.iast.core.utils;

import io.dongtai.iast.common.enums.HttpMethods;
import io.dongtai.iast.common.utils.AbstractHttpClientUtils;
import io.dongtai.iast.core.EngineManager;

import java.util.HashMap;
import java.util.Map;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class HttpClientUtils extends AbstractHttpClientUtils {
    private static final int MAX_RETRIES = 10;

    private static final PropertyUtils PROPERTIES = PropertyUtils.getInstance();
    private static String proxyHost = "";
    private static int proxyPort = -1;

    private static final HttpClientExceptionHandler EXCEPTION_HANDLER = new HttpClientExceptionHandler() {
        @Override
        public void run() {
            EngineManager.turnOffEngine();
        }
    };

    static {
        if (PROPERTIES.isProxyEnable()) {
            proxyHost = PROPERTIES.getProxyHost();
            proxyPort = PROPERTIES.getProxyPort();
        }
    }


    public static StringBuilder sendGet(String uri, Map<String, String> parameters) {
        if (parameters != null && !parameters.isEmpty()) {
            StringBuilder uriBuilder = new StringBuilder(uri);
            uriBuilder.append("?");
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                uriBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
            uri = uriBuilder.toString();
        }

        Map<String, String> headers = new HashMap<String, String>();
        setToken(headers);

        return sendRequest(HttpMethods.GET, PROPERTIES.getBaseUrl() + uri, null, headers, MAX_RETRIES,
                proxyHost, proxyPort, EXCEPTION_HANDLER);
    }

    public static StringBuilder sendPost(String uri, String value) {
        Map<String, String> headers = new HashMap<String, String>();
        setToken(headers);
        headers.put(HEADER_CONTENT_TYPE, MEDIA_TYPE_APPLICATION_JSON);
        headers.put(HEADER_CONTENT_ENCODING, REQUEST_ENCODING_TYPE);

        return sendRequest(HttpMethods.POST, PROPERTIES.getBaseUrl() + uri, value, headers, MAX_RETRIES,
                proxyHost, proxyPort, EXCEPTION_HANDLER);
    }

    public static boolean downloadRemoteJar(String fileURI, String fileName) {
        Map<String, String> headers = new HashMap<String, String>();
        setToken(headers);

        return downloadFile(PROPERTIES.getBaseUrl() + fileURI, fileName, headers, proxyHost, proxyPort);
    }

    private static void setToken(Map<String, String> headers) {
        headers.put(REQUEST_HEADER_TOKEN_KEY, "Token " + PROPERTIES.getIastServerToken());
    }
}
