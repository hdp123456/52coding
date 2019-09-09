package com.coding.happy.go.misc.api.impl;

import com.alibaba.fastjson.JSONObject;
import com.coding.happy.go.common.exception.MyException;
import com.coding.happy.go.common.utils.FileUploadUitl;
import com.coding.happy.go.common.utils.NewResponseUtil;
import com.coding.happy.go.common.utils.ValidatorUtil;
import com.coding.happy.go.mapper.common.CustomerBasicInfoPo;
import com.coding.happy.go.mapper.common.WechatKeyConfigPo;
import com.coding.happy.go.misc.api.ApiCustomerBasicInfoService;
import com.coding.happy.go.misc.client.PrivilegeGetwayClient;
import com.coding.happy.go.model.api.facade.ApiUnionIdAppFacade;
import com.coding.happy.go.model.api.facade.ApiUpdateCustomerAlipayNo;
import com.coding.happy.go.model.api.facade.ApiUpdateCustomerGender;
import com.coding.happy.go.model.api.facade.ApiUpdateCustomerHeadPicture;
import com.coding.happy.go.model.api.facade.ApiUpdateCustomerIdentity;
import com.coding.happy.go.model.api.facade.ApiUpdateCustomerNickname;
import com.coding.happy.go.model.api.vo.ApiCustomerBasicInfoVo;
import com.coding.happy.go.service.CustomerBasicInfoService;
import com.coding.happy.go.service.DailyReceiveStepsService;
import com.coding.happy.go.service.GoLevelConfigService;
import com.coding.happy.go.service.WechatKeyConfigService;
import com.vdurmont.emoji.EmojiParser;
import java.util.List;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
public class ApiCustomerBasicInfoServiceImpl implements ApiCustomerBasicInfoService {

    @Value("${upload.filePath.img:}")
    private String filePathImg;
    @Resource
    CustomerBasicInfoService customerBasicInfoService;
    @Resource
    DailyReceiveStepsService dailyReceiveStepsService;
    @Resource
    GoLevelConfigService goLevelConfigService;
    @Resource
    PrivilegeGetwayClient privilegeGetwayClient;
    @Resource
    WechatKeyConfigService wechatKeyConfigService;


    @Override
    public NewResponseUtil bindUnionId(String customerId, String appId,
        ApiUnionIdAppFacade unionIdAppFacade) {
        String unionId = customerBasicInfoService.getUnionIdByCustomerId(customerId);
        if (StringUtils.isNotBlank(unionId)) {
            return NewResponseUtil.newFailureResponse("已绑定微信");
        }
        //1.获取微信授权数据
        String code = unionIdAppFacade.getCode();
        JSONObject jsonObject = null;
        List<WechatKeyConfigPo> wechatKeyConfigPoList = wechatKeyConfigService
            .getAllWechatKeyConfigInfo();
        for (WechatKeyConfigPo wechatKeyConfigPo : wechatKeyConfigPoList) {
            //appIdDesc包名示例：com.huiydzq.app
            String appIdDesc = wechatKeyConfigPo.getAppId();
            if (appIdDesc.equals(appId)) {
                jsonObject = privilegeGetwayClient
                    .getAppUnionId(wechatKeyConfigPo.getAppKey(), wechatKeyConfigPo.getSecretKey(),
                        code, "authorization_code");
                break;
            }
        }
        log.info("jsonObject:{}", jsonObject != null ? jsonObject.toJSONString() : null);
        if (null == jsonObject) {
            return NewResponseUtil.newFailureResponse("获取微信权限失败，请稍后重试!");
        }
        String errcode = jsonObject.getString("errcode");
        String errmsg = jsonObject.getString("errmsg");
        if (StringUtils.isNotBlank(errcode) || StringUtils.isNotBlank(errmsg)) {
            return NewResponseUtil.newFailureResponse("获取微信权限失败，请稍后重试!");
        }
        String accessToken = jsonObject.getString("access_token");
        String openid = jsonObject.getString("openid");
        String unionid = jsonObject.getString("unionid");
        if (StringUtils.isBlank(unionid)) {
            return NewResponseUtil.newFailureResponse("获取微信权限失败，请稍后重试!");
        }
        log.info("app-unionid{}", unionid);

        //第一次用户微信授权
        JSONObject userInfo = privilegeGetwayClient
            .getUserInfo(openid, accessToken, "zh_CN");
        String errorcode = userInfo.getString("errcode");
        String errormsg = userInfo.getString("errmsg");
        if (StringUtils.isNotBlank(errorcode) || StringUtils.isNotBlank(errormsg)) {
            log.error("获取微信用户信息失败,错误原因:" + errormsg);
            throw new MyException("获取微信用户信息失败!");
        }
        String nick = userInfo.getString("nickname");
        String nickname = EmojiParser.removeAllEmojis(nick);
        //用户的性别，值为1时是男性，值为2时是女性，值为0时是未知
        Integer sex = userInfo.getInteger("sex");
        String headimgurl = userInfo.getString("headimgurl");
        CustomerBasicInfoPo customerBasicInfo = new CustomerBasicInfoPo();
        customerBasicInfo.setUnionId(unionId);
        customerBasicInfo.setWechatId(openid);
        customerBasicInfo.setCustomerId(customerId);
        customerBasicInfo.setNickname(nickname);
        customerBasicInfo.setSex(sex);
        customerBasicInfo.setHeadPicture(headimgurl);
        customerBasicInfoService.updateCustomerBasicInfoByCustomerId(customerBasicInfo);
        return NewResponseUtil.newSucceedResponse();
    }
}
