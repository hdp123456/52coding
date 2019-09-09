package tk.mybatis.springboot.utils;

import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeAppPayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeAppPayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tk.mybatis.springboot.bo.AlipayQueryBo;
import tk.mybatis.springboot.facade.AlipayOrderFacade;

/**
 * 支付宝支付
 */
@Component
@Slf4j
public class AlipayUtil {

    //应用appid
    @Value("${alipay.app_id}")
    private String APP_ID;
    //app私钥
    @Value("${alipay.app_private_key}")
    private String APP_PRIVATE_KEY;
    //支付宝公钥
    @Value("${alipay.alipay_public_key}")
    private String ALIPAY_PUBLIC_KEY;
    //支付网关地址
    @Value("${alipay.server_url}")
    private String SERVER_URL;
    //签约产品码
    @Value("${alipay.product_code}")
    private String PRODUCT_CODE;
    @Value("${alipay.notify_url}")
    private String NOTIFY_URL;

    //订单过期时间,取值范围：1m～15d。m-分钟，h-小时，d-天
    static String TIMEOUT_EXPRESS = "30m";
    static String CHARSET = "utf-8";

    static String PROJECT_NAME = "星动";

    /**
     * app支付
     */
    public String tradeAppPayRequest(AlipayOrderFacade facade)
        throws AlipayApiException {
        AlipayClient alipayClient = new DefaultAlipayClient(
            SERVER_URL, APP_ID, APP_PRIVATE_KEY,
            "json", CHARSET, ALIPAY_PUBLIC_KEY, "RSA2");
        AlipayTradeAppPayRequest request = new AlipayTradeAppPayRequest();
        request.setNotifyUrl(NOTIFY_URL);
        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
        request.setBizModel(model);
        //商号订单号 最大64位 必填
        model.setOutTradeNo(facade.getOrderNo());
        //订单总金额，单位为元，精确到小数点后两位  必填
        model.setTotalAmount(facade.getPayAmount());
        //订单标题 必填
        model.setSubject(PROJECT_NAME + "-" + facade.getOrderName());
        //对交易或商品的描述
        model.setBody(facade.getOrderName());
        //该笔订单允许的最晚付款时间，逾期将关闭交易。取值范围：1m～15d。m-分钟，h-小时，d-天
        model.setTimeoutExpress(TIMEOUT_EXPRESS);
        model.setQrCodeTimeoutExpress(TIMEOUT_EXPRESS);
        //销售产品码，商家和支付宝签约的产品码，为固定值QUICK_MSECURITY_PAY 必填
        model.setProductCode(PRODUCT_CODE);
        log.info("支付宝app支付|创建支付订单信息:{}", JSON.toJSONString(request));
        AlipayTradeAppPayResponse response = alipayClient.sdkExecute(request);
        log.info("支付宝app支付|创建支付订单应答结果:{}", JSON.toJSONString(response));
        //返回数据
        if (response.isSuccess()) {
            // 获取到getBody直接给app,用这个东西去调起支付宝
            return response.getBody();
        } else {
            throw new AlipayApiException("支付宝app支付|创建支付订单信息失败！");
        }
    }

    /**
     * 扫码支付
     */
    public String tradeQrcodePayRequest(AlipayOrderFacade facade) throws AlipayApiException {
        AlipayClient alipayClient = new DefaultAlipayClient(SERVER_URL, APP_ID, APP_PRIVATE_KEY,
            "json", CHARSET, ALIPAY_PUBLIC_KEY, "RSA2");
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
        request.setBizModel(model);
        request.setNotifyUrl(NOTIFY_URL);
        //商号订单号 最大64位 必填
        model.setOutTradeNo(facade.getOrderNo());
        //订单总金额，单位为元，精确到小数点后两位  必填
        model.setTotalAmount(facade.getPayAmount());
        //订单标题 必填
        model.setSubject(PROJECT_NAME + "-" + facade.getOrderName());
        //对交易或商品的描述
        model.setBody(facade.getOrderName());
        //该笔订单允许的最晚付款时间，逾期将关闭交易。取值范围：1m～15d。m-分钟，h-小时，d-天
        model.setTimeoutExpress(TIMEOUT_EXPRESS);
        model.setQrCodeTimeoutExpress(TIMEOUT_EXPRESS);
        log.info("支付宝扫码支付|创建支付订单信息:{}", JSON.toJSONString(request));
        AlipayTradePrecreateResponse response = alipayClient.execute(request);
        log.info("支付宝扫码支付|创建支付订单应答结果:{}", JSON.toJSONString(response));
        //返回数据
        if (response.isSuccess()) {
            return response.getQrCode();
        } else {
            throw new AlipayApiException("支付宝扫码支付|创建支付订单信息失败！");
        }
    }

    /**
     * app支付：支付宝异步通知验签及解析
     */
    public Map<String, String> checkNoticeParam(HttpServletRequest request)
        throws AlipayApiException {
        //获取支付宝POST过来反馈信息
        Map<String, String> params = new HashMap<>();
        Map requestParams = request.getParameterMap();
        for (Iterator iter = requestParams.keySet().iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            String[] values = (String[]) requestParams.get(name);
            String valueStr = "";
            for (int i = 0; i < values.length; i++) {
                valueStr = (i == values.length - 1) ? valueStr + values[i]
                    : valueStr + values[i] + ",";
            }
            //乱码解决，这段代码在出现乱码时使用。
            //valueStr = new String(valueStr.getBytes("ISO-8859-1"), "utf-8");
            params.put(name, valueStr);
        }
        log.info("支付宝异步通知|应答参数:{}", JSON.toJSONString(params));
        boolean flag = AlipaySignature.rsaCheckV1(params, ALIPAY_PUBLIC_KEY, CHARSET, "RSA2");
        log.info("支付宝异步通知|验签结果:flag={}", flag);
        if (flag) {
            return params;
        }
        return null;
    }

    /**
     * 交易查询接口
     */
    public AlipayQueryBo queryPayStatus(String orderNo) throws AlipayApiException {
        AlipayClient alipayClient = new DefaultAlipayClient(SERVER_URL, APP_ID, APP_PRIVATE_KEY,
            "json", CHARSET, ALIPAY_PUBLIC_KEY, "RSA2");
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        request.setBizModel(model);
        model.setOutTradeNo(orderNo);
        log.info("支付宝交易查询接口|请求参数:{}", JSON.toJSONString(request));
        AlipayTradeQueryResponse response = alipayClient
            .execute(request);
        log.info("支付宝交易查询接口|应答结果:{}", JSON.toJSONString(response));
        //返回数据
        if (response.isSuccess()) {
            AlipayQueryBo bo = new AlipayQueryBo();
            bo.setTradeStatus(response.getTradeStatus());
            bo.setOutTradeNo(response.getOutTradeNo());
            bo.setTradeNo(response.getTradeNo());
            bo.setReceiptAmount(response.getReceiptAmount());
            return bo;
        }
        return null;
    }


}
