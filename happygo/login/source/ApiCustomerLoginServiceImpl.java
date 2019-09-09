package com.coding.happy.go.misc.api.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.coding.happy.go.common.constants.CoinRecordCodeConstants;
import com.coding.happy.go.common.constants.Constants;
import com.coding.happy.go.common.constants.CustomerConstants;
import com.coding.happy.go.common.constants.DeviceTypeConstants;
import com.coding.happy.go.common.constants.GoLevelStepsConstants;
import com.coding.happy.go.common.constants.LoginTypeContstants;
import com.coding.happy.go.common.constants.WxConstants;
import com.coding.happy.go.common.enums.GenderEnum;
import com.coding.happy.go.common.exception.MyException;
import com.coding.happy.go.common.utils.AESEncryptUtil;
import com.coding.happy.go.common.utils.AESUtil;
import com.coding.happy.go.common.utils.LoginTimeOutUtil;
import com.coding.happy.go.common.utils.NewResponseUtil;
import com.coding.happy.go.common.utils.NickNameUtil;
import com.coding.happy.go.common.utils.RedisCacheUtil;
import com.coding.happy.go.common.utils.TokenUtil;
import com.coding.happy.go.common.utils.UUIDUitl;
import com.coding.happy.go.mapper.common.CustomerBasicInfoPo;
import com.coding.happy.go.mapper.common.CustomerCoinRecordPo;
import com.coding.happy.go.mapper.common.CustomerCommonInfoPo;
import com.coding.happy.go.mapper.common.CustomerGoLevelPo;
import com.coding.happy.go.mapper.common.LoginLogoutRecordPo;
import com.coding.happy.go.mapper.common.WechatKeyConfigPo;
import com.coding.happy.go.misc.api.ApiCustomerLoginService;
import com.coding.happy.go.misc.client.OldHappyGoSystemClient;
import com.coding.happy.go.misc.client.PrivilegeGetwayClient;
import com.coding.happy.go.misc.common.CommonSmsSendService;
import com.coding.happy.go.model.api.bo.WechatUserInfoBo;
import com.coding.happy.go.model.api.facade.ApiPhoneDataFacade;
import com.coding.happy.go.model.api.facade.ApiSmsFacade;
import com.coding.happy.go.model.api.facade.ApiUnionIdAppFacade;
import com.coding.happy.go.model.api.facade.ApiUnionIdProgramBindPhoneFacade;
import com.coding.happy.go.model.api.facade.ApiUnionIdProgramFacade;
import com.coding.happy.go.model.api.facade.ApiVerifyPhoneDataFacade;
import com.coding.happy.go.model.client.bo.ClientCustomerBasicInfoBo;
import com.coding.happy.go.model.client.vo.ClientGetCustomerDataVo;
import com.coding.happy.go.service.CustomerBasicInfoService;
import com.coding.happy.go.service.CustomerCoinRecordService;
import com.coding.happy.go.service.CustomerCommonInfoService;
import com.coding.happy.go.service.CustomerGoLevelService;
import com.coding.happy.go.service.LoginLogoutRecordService;
import com.coding.happy.go.service.WechatKeyConfigService;
import com.vdurmont.emoji.EmojiParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
public class ApiCustomerLoginServiceImpl implements ApiCustomerLoginService {

    @Resource
    CommonSmsSendService commonSmsSendService;
    @Resource
    RedisCacheUtil redisCacheUtil;
    @Resource
    CustomerBasicInfoService customerBasicInfoService;
    @Resource
    LoginLogoutRecordService loginLogoutRecordService;
    @Resource
    CustomerCommonInfoService customerCommonInfoService;
    @Resource
    CustomerGoLevelService customerGoLevelService;
    @Resource
    OldHappyGoSystemClient oldHappyGoSystemClient;
    @Resource
    PrivilegeGetwayClient privilegeGetwayClient;
    @Resource
    CustomerCoinRecordService customerCoinRecordService;
    @Resource
    WechatKeyConfigService wechatKeyConfigService;

    @Override
    public NewResponseUtil sendPhoneData(ApiPhoneDataFacade facade) {
        String phone = facade.getPhoneData();
        if (Constants.WHITE_PHONE.contains(phone)) {
            //请求成功,缓存30分钟
            redisCacheUtil.putCacheWithExpireTime(phone, Constants.WHITE_PHONE_CODE, 60 * 30);
            return NewResponseUtil.newSucceedResponse();
        }
        //判断短信发送次数
        LocalDate now = LocalDate.now();
        String redisKey = phone + "-" + now;
        Long number = redisCacheUtil.getSmsIncrValue(redisKey);
        if (number > 50) {
            return NewResponseUtil.newFailureResponse("短信发送次数超过当日上限!");
        }

        //用户微信登录或小程序登录!
        if (StringUtils.isNotBlank(facade.getWechatUserInfoBo())) {
            WechatUserInfoBo wechatUserInfoBo = JSON
                .parseObject(facade.getWechatUserInfoBo(), WechatUserInfoBo.class);
            CustomerBasicInfoPo customerBasicInfoPo = customerBasicInfoService
                .getCustomerBasicInfoByUnionId(wechatUserInfoBo.getUnionId());
            if (null != customerBasicInfoPo && !customerBasicInfoPo.getPhone().equals(phone)) {
                return NewResponseUtil
                    .newFailureResponse("您的手机号码已绑定过其他微信账号,无法再次被绑定,您可以选择手机号码直接登录!");
            }
        }

        String code = UUIDUitl.generateInteger(6);
        if (commonSmsSendService.sendSmsCode(phone, code)) {
            //请求成功,缓存30分钟
            redisCacheUtil.putCacheWithExpireTime(phone, code, 60 * 30);
            return NewResponseUtil.newSucceedResponse();
        }
        return NewResponseUtil.newFailureResponse("短信发送异常，请稍后重试!");
    }

    @Override
    public NewResponseUtil smsLogin(String deviceType, ApiSmsFacade facade) throws Exception {
        String code = facade.getCode();
        String phone = facade.getPhone();
        String realCode = redisCacheUtil.getCache(phone, String.class);
        if (StringUtils.isBlank(realCode)) {
            return NewResponseUtil.newFailureResponse("验证码已失效或未发送，请重新发送!");
        }
        if (!realCode.equals(code)) {
            return NewResponseUtil.newFailureResponse("验证码错误，请稍后重试!");
        }
        //用户微信登录或小程序登录!
        WechatUserInfoBo wechatUserInfoBo = null;
        if (StringUtils.isNotBlank(facade.getWechatUserInfoBo())) {
            wechatUserInfoBo = JSON
                .parseObject(facade.getWechatUserInfoBo(), WechatUserInfoBo.class);
            CustomerBasicInfoPo customerBasicInfoPo = customerBasicInfoService
                .getCustomerBasicInfoByUnionId(wechatUserInfoBo.getUnionId());
            if (null != customerBasicInfoPo && !customerBasicInfoPo.getPhone().equals(phone)) {
                return NewResponseUtil
                    .newFailureResponse("您的手机号码已绑定过其他微信账号,无法再次被绑定,您可以选择手机号码直接登录!");
            }
        }

        //邀请码校验
        String invitationCode = facade.getInvitationCode();
        if (StringUtils.isNotBlank(invitationCode)) {
            int rows = customerBasicInfoService
                .getCountCustomerBasicInfoByInvitationCode(invitationCode);
            if (rows == 0) {
                return NewResponseUtil.newFailureResponse("邀请码错误");
            }
        }

        CustomerBasicInfoPo customerBasicInfoPo = customerBasicInfoService
            .getCustomerBasicInfoByPhone(phone);
        //新用户注册登录流程
        if (null == customerBasicInfoPo) {
            String customerId = UUIDUitl.generateLowerString(32);
            //1.根据手机号，判断是否老系统用户.(老用户则需同步数据至当前系统)
            Integer state = syncOldSystemCustomerDataByPhone(phone, customerId, wechatUserInfoBo,
                invitationCode);

            //2.手机号不存在于老系统（全新用户）
            if (state.equals(0)) {
                addNewSystemCustomerDataByPhone(customerId, phone, deviceType, wechatUserInfoBo,
                    invitationCode);
            }

            //3.登录token相关逻辑
            String token = TokenUtil.generateToken(customerId);
            //插入 登录登出记录表
            LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
            LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
            loginLogoutRecordPo.setCustomerId(customerId);
            loginLogoutRecordPo.setToken(token);
            loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
            loginLogoutRecordPo.setLogoutTime(logoutTime);
            loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
            //返回数据
            JSONObject json = new JSONObject();
            json.put("token", token);
            return NewResponseUtil.newSucceedResponse(json);
        }

        //新系统的已存在用户登录流程
        String customerId = customerBasicInfoPo.getCustomerId();
        CustomerCommonInfoPo customerCommonInfoPo = new CustomerCommonInfoPo();
        customerCommonInfoPo.setCustomerId(customerId);
        if (null != wechatUserInfoBo) {
            customerCommonInfoPo.setSessionKey(wechatUserInfoBo.getSessionKey());
            customerCommonInfoService.updateCustomerCommonInfoByCustomerId(customerCommonInfoPo);
        }
        if (null != wechatUserInfoBo && StringUtils.isBlank(customerBasicInfoPo.getUnionId())) {
            customerBasicInfoPo.setUnionId(wechatUserInfoBo.getUnionId());
            if (wechatUserInfoBo.getType().equals(LoginTypeContstants.WECHAT_LOGIN)) {
                customerBasicInfoPo.setWechatId(wechatUserInfoBo.getOpenId());
            } else {
                customerBasicInfoPo.setAppId(wechatUserInfoBo.getOpenId());
            }
            customerBasicInfoPo.setSex(wechatUserInfoBo.getSex());
            customerBasicInfoPo.setHeadPicture(wechatUserInfoBo.getHeadPicture());
            customerBasicInfoPo.setNickname(wechatUserInfoBo.getNickname());
            customerBasicInfoService.updateCustomerBasicInfoByCustomerId(customerBasicInfoPo);
        }
        LoginLogoutRecordPo oldLoginLogoutRecordPo = loginLogoutRecordService
            .getTokenInfoByCustomerId(customerId, LocalDateTime.now());
        if (null != oldLoginLogoutRecordPo) {
            //返回数据
            JSONObject json = new JSONObject();
            json.put("token", oldLoginLogoutRecordPo.getToken());
            return NewResponseUtil.newSucceedResponse(json);
        }
        //生成新token
        String token = TokenUtil.generateToken(customerId);
        //插入 登录登出记录表
        LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
        LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
        loginLogoutRecordPo.setCustomerId(customerId);
        loginLogoutRecordPo.setToken(token);
        loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
        loginLogoutRecordPo.setLogoutTime(logoutTime);
        loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
        //返回数据
        JSONObject json = new JSONObject();
        json.put("token", token);
        return NewResponseUtil.newSucceedResponse(json);
    }

    /**
     * 初始化新系统用户数据
     */
    private void addNewSystemCustomerDataByPhone(String customerId, String phone,
        String deviceType, WechatUserInfoBo wechatUserInfoBo, String invitationCode) {
        //插入 用户信息表
        CustomerBasicInfoPo customerBasicInfoPo = new CustomerBasicInfoPo();
        if (null != wechatUserInfoBo) {
            customerBasicInfoPo.setUnionId(wechatUserInfoBo.getUnionId());
            if (wechatUserInfoBo.getType().equals(LoginTypeContstants.WECHAT_LOGIN)) {
                customerBasicInfoPo.setWechatId(wechatUserInfoBo.getOpenId());
            } else {
                customerBasicInfoPo.setAppId(wechatUserInfoBo.getOpenId());
            }
            customerBasicInfoPo.setSex(wechatUserInfoBo.getSex());
            customerBasicInfoPo.setHeadPicture(wechatUserInfoBo.getHeadPicture());
            customerBasicInfoPo.setNickname(wechatUserInfoBo.getNickname());
            customerBasicInfoPo.setRegisterSource(DeviceTypeConstants.WXAPP_VALUE);
        } else {
            String nickName = NickNameUtil.getRandomNickName();
            customerBasicInfoPo.setNickname(nickName);
            customerBasicInfoPo
                .setRegisterSource(DeviceTypeConstants.DEVICE_CHANNEL_MAP.get(deviceType));
        }
        customerBasicInfoPo.setPhone(phone);
        customerBasicInfoPo.setCustomerId(customerId);
        customerBasicInfoPo.setRegisterTime(LocalDateTime.now());
        customerBasicInfoPo.setRegisterDate(LocalDate.now());
        customerBasicInfoPo.setInvitationCode(UUIDUitl.generateUpperString(6));
        if (StringUtils.isNotBlank(invitationCode)) {
            customerBasicInfoPo.setInvitedCode(invitationCode);
        }
        customerBasicInfoService.addCustomerBasicInfo(customerBasicInfoPo);
        //插入 用户综合信息表
        CustomerCommonInfoPo customerCommonInfoPo = new CustomerCommonInfoPo();
        customerCommonInfoPo.setCustomerId(customerId);
        if (null != wechatUserInfoBo) {
            customerCommonInfoPo.setSessionKey(wechatUserInfoBo.getSessionKey());
        }
        customerCommonInfoService.addCustomerCommonInfo(customerCommonInfoPo);
        //插入 用户乐走勋章级别
        CustomerGoLevelPo customerGoLevelPo = new CustomerGoLevelPo();
        customerGoLevelPo.setCustomerId(customerId);
        customerGoLevelPo.setLevelCode(GoLevelStepsConstants.BRONZE_LEVEL);
        customerGoLevelPo.setIsAlert(1);
        customerGoLevelPo.setHaveDate(LocalDate.now());
        customerGoLevelService.addCustomerGoLevel(customerGoLevelPo);
    }

    /**
     * 判断手机号是否在老系统存在，存在则同步数据至当前系统,删除老系统用户。
     * 返回值 0-手机号存在老系统中; 1表示手机号不存在老系统中;
     */
    @Override
    public Integer syncOldSystemCustomerDataByPhone(String phone, String customerId,
        WechatUserInfoBo wechatUserInfoBo, String invitationCode) {
        NewResponseUtil<ClientGetCustomerDataVo> responseUtil = oldHappyGoSystemClient
            .getCustomerDataByPhone(phone);
        if (null == responseUtil || !NewResponseUtil.SUCCESS.equals(responseUtil.getCode())) {
            log.error("乐走老系统调用异常| 获取老系统用户数据失败!");
            throw new MyException("用户数据迁移失败");
        }
        ClientGetCustomerDataVo vo = responseUtil.getResult();
        Integer state = vo.getState();
        ClientCustomerBasicInfoBo customerBasicInfoBo = vo.getCustomerBasicInfoBo();
        BigDecimal coins = vo.getCoins();
        LocalDate lastReceiveDate = vo.getLastReceiveDate();
        BigDecimal morningCoins = vo.getMorningCoins();
        if (state.equals(0)) {
            return 0;
        }
        //插入 用户信息表
        CustomerBasicInfoPo customerBasicInfoPo = new CustomerBasicInfoPo();
        customerBasicInfoPo.setCustomerId(customerId);
        if (null != wechatUserInfoBo) {
            customerBasicInfoPo.setUnionId(wechatUserInfoBo.getUnionId());
            if (StringUtils.isBlank(customerBasicInfoPo.getWechatId())
                && wechatUserInfoBo.getType().equals(LoginTypeContstants.WECHAT_LOGIN)) {
                customerBasicInfoPo.setWechatId(wechatUserInfoBo.getOpenId());
            } else if (StringUtils.isBlank(customerBasicInfoPo.getAppId())) {
                customerBasicInfoPo.setAppId(wechatUserInfoBo.getOpenId());
            }
        }
        customerBasicInfoPo.setPhone(phone);
        customerBasicInfoPo.setNickname(customerBasicInfoBo.getNickname());
        customerBasicInfoPo.setSex(GenderEnum.MALE.getCode());
        customerBasicInfoPo.setRegisterSource(DeviceTypeConstants.ANDROID_VALUE);
        customerBasicInfoPo.setRegisterTime(LocalDateTime.of(2019, 7, 1, 0, 0, 0));
        customerBasicInfoPo.setRegisterDate(LocalDate.of(2019, 7, 1));
        customerBasicInfoPo.setInvitationCode(UUIDUitl.generateUpperString(6));
        if (StringUtils.isNotBlank(invitationCode)) {
            customerBasicInfoPo.setInvitedCode(invitationCode);
        }
        customerBasicInfoService.addCustomerBasicInfo(customerBasicInfoPo);
        //插入 用户综合信息表
        CustomerCommonInfoPo customerCommonInfoPo = new CustomerCommonInfoPo();
        customerCommonInfoPo.setCustomerId(customerId);
        customerCommonInfoPo.setBalanceCoin(coins.add(morningCoins));
        customerCommonInfoPo.setTotalEarnCoin(coins.add(morningCoins));
        customerCommonInfoPo.setUserType(2);
        if (null != lastReceiveDate) {
            customerCommonInfoPo.setReceiveDate(lastReceiveDate);
        }
        if (null != wechatUserInfoBo) {
            customerCommonInfoPo.setSessionKey(wechatUserInfoBo.getSessionKey());
        }
        customerCommonInfoService.addCustomerCommonInfo(customerCommonInfoPo);
        //插入乐币明细
        if (coins.compareTo(BigDecimal.ZERO) > 0) {
            CustomerCoinRecordPo customerCoinRecordPo = new CustomerCoinRecordPo();
            customerCoinRecordPo.setCustomerId(customerId);
            customerCoinRecordPo.setRecordCode(CoinRecordCodeConstants.OLD_SYSTEM_BALANCE);
            customerCoinRecordPo.setType(CustomerConstants.COIN_INCOMDE);
            customerCoinRecordPo.setCoin(coins);
            customerCoinRecordService.addCustomerCoinRecord(customerCoinRecordPo);
        }
        if (morningCoins.compareTo(BigDecimal.ZERO) > 0) {
            CustomerCoinRecordPo customerCoinRecordPo = new CustomerCoinRecordPo();
            customerCoinRecordPo.setCustomerId(customerId);
            customerCoinRecordPo.setRecordCode(CoinRecordCodeConstants.OLD_SYSTEM_MORNING);
            customerCoinRecordPo.setType(CustomerConstants.COIN_INCOMDE);
            customerCoinRecordPo.setCoin(coins);
            customerCoinRecordService.addCustomerCoinRecord(customerCoinRecordPo);
        }

        //插入 用户乐走勋章级别
        CustomerGoLevelPo customerGoLevelPo = new CustomerGoLevelPo();
        customerGoLevelPo.setCustomerId(customerId);
        customerGoLevelPo.setLevelCode(GoLevelStepsConstants.BRONZE_LEVEL);
        customerGoLevelPo.setIsAlert(2);
        customerGoLevelPo.setHaveDate(LocalDate.now());
        customerGoLevelService.addCustomerGoLevel(customerGoLevelPo);
        //删除老系统用户
        oldHappyGoSystemClient.deleteCustomerByPhone(phone);
        return 1;
    }

    @Override
    public NewResponseUtil accessUnionId(String appId, ApiUnionIdAppFacade unionIdAppFacade)
        throws Exception {
        JSONObject json = new JSONObject();
        json.put("noPhone", 0);//0无需手机号登录;1 强制手机号登录
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

        CustomerBasicInfoPo customerBasicInfoPo = null;
        customerBasicInfoPo = customerBasicInfoService.getCustomerBasicInfoByUnionId(unionid);
        //2.用户在当前系统存在
        if (null != customerBasicInfoPo) {
            String customerId = customerBasicInfoPo.getCustomerId();
            if (StringUtils.isBlank(customerBasicInfoPo.getWechatId())) {
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
                customerBasicInfo.setUnionId(unionid);
                customerBasicInfo.setWechatId(openid);
                customerBasicInfo.setCustomerId(customerId);
                customerBasicInfo.setNickname(nickname);
                customerBasicInfo.setSex(sex);
                customerBasicInfo.setHeadPicture(headimgurl);
                customerBasicInfoService.updateCustomerBasicInfoByCustomerId(customerBasicInfo);
            }
            LoginLogoutRecordPo oldLoginLogoutRecordPo = loginLogoutRecordService
                .getTokenInfoByCustomerId(customerId, LocalDateTime.now());
            if (null != oldLoginLogoutRecordPo) {
                //返回数据
                json.put("token", oldLoginLogoutRecordPo.getToken());
                return NewResponseUtil.newSucceedResponse(json);
            }
            //生成新token
            String token = TokenUtil.generateToken(customerId);
            //插入 登录登出记录表
            LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
            LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
            loginLogoutRecordPo.setCustomerId(customerId);
            loginLogoutRecordPo.setToken(token);
            loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
            loginLogoutRecordPo.setLogoutTime(logoutTime);
            loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
            //返回数据
            json.put("token", token);
            return NewResponseUtil.newSucceedResponse(json);
        }

        //3.判断用户是否在老系统中(老用户则需同步数据至当前系统)
        String customerId = UUIDUitl.generateLowerString(32);
        Integer state = syncOldSystemCustomerDataByUnionId(unionid, customerId, null);
        //强制用户进行手机号登录流程
        if (state.equals(0)) {
            //获取微信授权数据并加密，返回至客户端
            JSONObject userInfo = privilegeGetwayClient.getUserInfo(openid, accessToken, "zh_CN");
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
            WechatUserInfoBo wechatUserInfoBo = WechatUserInfoBo.builder()
                .type(LoginTypeContstants.WECHAT_LOGIN).unionId(unionid).openId(openid)
                .nickname(nickname).sex(sex).headPicture(headimgurl)
                .build();
            //返回数据
            String enc = AESEncryptUtil.encryptAES(JSON.toJSONString(wechatUserInfoBo));
            json.put("noPhone", 1);//需要绑定手机号
            json.put("wechatUserInfoBo", enc);
            return NewResponseUtil.newSucceedResponse(json);
        }

        //4.老系统同步数据至当前系统后，进行登录token相关逻辑
        String token = TokenUtil.generateToken(customerId);
        //插入 登录登出记录表
        LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
        LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
        loginLogoutRecordPo.setCustomerId(customerId);
        loginLogoutRecordPo.setToken(token);
        loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
        loginLogoutRecordPo.setLogoutTime(logoutTime);
        loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
        //返回数据
        json.put("token", token);
        return NewResponseUtil.newSucceedResponse(json);
    }

    /**
     * 判断微信unionId是否在老系统存在，存在则同步数据至当前系统,删除老系统用户。
     * 返回值 0表示unionId存在老系统中; 1表示unionId不存在老系统中;
     */
    @Override
    public Integer syncOldSystemCustomerDataByUnionId(String unionId, String customerId,
        String sessionKey) {
        NewResponseUtil<ClientGetCustomerDataVo> responseUtil = oldHappyGoSystemClient
            .getCustomerDataByUnionId(unionId);
        if (null == responseUtil || !NewResponseUtil.SUCCESS.equals(responseUtil.getCode())) {
            log.error("乐走老系统调用异常| 获取老系统用户数据失败!");
            throw new MyException("用户数据迁移失败");
        }
        ClientGetCustomerDataVo vo = responseUtil.getResult();
        Integer state = vo.getState();
        ClientCustomerBasicInfoBo customerBasicInfoBo = vo.getCustomerBasicInfoBo();
        BigDecimal coins = vo.getCoins();
        LocalDate lastReceiveDate = vo.getLastReceiveDate();
        BigDecimal morningCoins = vo.getMorningCoins();
        if (state.equals(0)) {
            return 0;
        }
        //插入 用户信息表
        CustomerBasicInfoPo customerBasicInfoPo = new CustomerBasicInfoPo();
        customerBasicInfoPo.setCustomerId(customerId);
        customerBasicInfoPo.setWechatId(customerBasicInfoBo.getWechatId());
        customerBasicInfoPo.setAppId(customerBasicInfoBo.getAppId());
        customerBasicInfoPo.setUnionId(customerBasicInfoBo.getUnionId());
        customerBasicInfoPo.setNickname(customerBasicInfoBo.getNickname());
        customerBasicInfoPo.setSex(customerBasicInfoBo.getSex());
        customerBasicInfoPo.setHeadPicture(customerBasicInfoBo.getHeadPicture());
        customerBasicInfoPo.setRegisterSource(DeviceTypeConstants.ANDROID_VALUE);
        customerBasicInfoPo.setRegisterTime(LocalDateTime.of(2019, 7, 1, 0, 0, 0));
        customerBasicInfoPo.setRegisterDate(LocalDate.of(2019, 7, 1));
        customerBasicInfoPo.setInvitationCode(UUIDUitl.generateUpperString(6));
        customerBasicInfoPo.setInvitedCode(customerBasicInfoBo.getInvitedCode());
        customerBasicInfoService.addCustomerBasicInfo(customerBasicInfoPo);
        //插入 用户综合信息表
        CustomerCommonInfoPo customerCommonInfoPo = new CustomerCommonInfoPo();
        customerCommonInfoPo.setCustomerId(customerId);
        customerCommonInfoPo.setBalanceCoin(coins.add(morningCoins));
        customerCommonInfoPo.setTotalEarnCoin(coins.add(morningCoins));
        customerCommonInfoPo.setUserType(2);
        if (null != lastReceiveDate) {
            customerCommonInfoPo.setReceiveDate(lastReceiveDate);
        }
        if (StringUtils.isNotBlank(sessionKey)) {
            customerCommonInfoPo.setSessionKey(sessionKey);
        }
        customerCommonInfoService.addCustomerCommonInfo(customerCommonInfoPo);
        //插入乐币明细
        if (coins.compareTo(BigDecimal.ZERO) > 0) {
            CustomerCoinRecordPo customerCoinRecordPo = new CustomerCoinRecordPo();
            customerCoinRecordPo.setCustomerId(customerId);
            customerCoinRecordPo.setRecordCode(CoinRecordCodeConstants.OLD_SYSTEM_BALANCE);
            customerCoinRecordPo.setType(CustomerConstants.COIN_INCOMDE);
            customerCoinRecordPo.setCoin(coins);
            customerCoinRecordService.addCustomerCoinRecord(customerCoinRecordPo);
        }
        if (morningCoins.compareTo(BigDecimal.ZERO) > 0) {
            CustomerCoinRecordPo customerCoinRecordPo = new CustomerCoinRecordPo();
            customerCoinRecordPo.setCustomerId(customerId);
            customerCoinRecordPo.setRecordCode(CoinRecordCodeConstants.OLD_SYSTEM_MORNING);
            customerCoinRecordPo.setType(CustomerConstants.COIN_INCOMDE);
            customerCoinRecordPo.setCoin(coins);
            customerCoinRecordService.addCustomerCoinRecord(customerCoinRecordPo);
        }
        //插入 用户乐走勋章级别
        CustomerGoLevelPo customerGoLevelPo = new CustomerGoLevelPo();
        customerGoLevelPo.setCustomerId(customerId);
        customerGoLevelPo.setLevelCode(GoLevelStepsConstants.BRONZE_LEVEL);
        customerGoLevelPo.setIsAlert(2);
        customerGoLevelPo.setHaveDate(LocalDate.now());
        customerGoLevelService.addCustomerGoLevel(customerGoLevelPo);
        //删除老系统用户
        oldHappyGoSystemClient.deleteCustomerByUnionId(unionId);
        return 1;
    }

    @Override
    public NewResponseUtil accessUnionId(ApiUnionIdProgramFacade unionIdProgramFacade)
        throws Exception {
        JSONObject json = new JSONObject();
        json.put("noPhone", 0);//0无需手机号登录;1 强制手机号登录
        //1.获取小程序授权数据
        String code = unionIdProgramFacade.getCode();
        JSONObject jsonObject = privilegeGetwayClient
            .getProgramUnionId(WxConstants.PROGRAMID, WxConstants.PROGRAMSECRET, code,
                "authorization_code");
        if (null == jsonObject) {
            return NewResponseUtil.newFailureResponse("获取小程序权限失败，请稍后重试!");
        }
        log.info("jsonObject:{}", jsonObject.toJSONString());
        String errcode = jsonObject.getString("errcode");
        String errmsg = jsonObject.getString("errmsg");
        if (StringUtils.isNotBlank(errcode) || StringUtils.isNotBlank(errmsg)) {
            return NewResponseUtil.newFailureResponse("获取小程序权限失败，请稍后重试!");
        }
        //sessionKey需存表并保持最新
        String openid = jsonObject.getString("openid");
        String sessionKey = jsonObject.getString("session_key");
        String result = null;
        try {
            result = AESUtil
                .decrypt(Base64.decodeBase64(unionIdProgramFacade.getEncryptedData()),
                    Base64.decodeBase64(sessionKey),
                    Base64.decodeBase64(unionIdProgramFacade.getIv()));
            log.info("小程序登录解密数据:{}", result);
        } catch (Exception e) {
            log.error("数据解密失败", e);
            return NewResponseUtil.newFailureResponse("数据解密失败!");
        }
        if (null == result) {
            return NewResponseUtil.newFailureResponse("数据解密失败!");
        }
        JSONObject js = JSONObject.parseObject(result);
        String unionid = js.getString("unionId");
        if (StringUtils.isBlank(unionid)) {
            return NewResponseUtil.newFailureResponse("获取小程序权限失败，请稍后重试!");
        }
        String headPicture = js.getString("avatarUrl");
        String nick = js.getString("nickName");
        String nickname = EmojiParser.removeAllEmojis(nick);
        Integer sex = js.getInteger("gender");
        log.info("program-unionid{}", unionid);

        CustomerBasicInfoPo customerBasicInfoPo = customerBasicInfoService
            .getCustomerBasicInfoByUnionId(unionid);
        //2.用户在当前系统存在
        if (null != customerBasicInfoPo) {
            String customerId = customerBasicInfoPo.getCustomerId();
            if (StringUtils.isBlank(customerBasicInfoPo.getAppId())) {
                CustomerBasicInfoPo customerBasicInfo = new CustomerBasicInfoPo();
                customerBasicInfo.setAppId(openid);
                customerBasicInfo.setCustomerId(customerId);
                customerBasicInfo.setNickname(nickname);
                customerBasicInfo.setHeadPicture(headPicture);
                customerBasicInfo.setSex(sex);
                customerBasicInfoService.updateCustomerBasicInfoByCustomerId(customerBasicInfo);
            }
            CustomerCommonInfoPo customerCommonInfoPo = new CustomerCommonInfoPo();
            customerCommonInfoPo.setCustomerId(customerId);
            customerCommonInfoPo.setSessionKey(sessionKey);
            customerCommonInfoService
                .updateCustomerCommonInfoByCustomerId(customerCommonInfoPo);
            LoginLogoutRecordPo oldLoginLogoutRecordPo = loginLogoutRecordService
                .getTokenInfoByCustomerId(customerId, LocalDateTime.now());
            if (null != oldLoginLogoutRecordPo) {
                //返回数据
                json.put("token", oldLoginLogoutRecordPo.getToken());
                return NewResponseUtil.newSucceedResponse(json);
            }
            //生成新token
            String token = TokenUtil.generateToken(customerId);
            //插入 登录登出记录表
            LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
            LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
            loginLogoutRecordPo.setCustomerId(customerId);
            loginLogoutRecordPo.setToken(token);
            loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
            loginLogoutRecordPo.setLogoutTime(logoutTime);
            loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
            //返回数据
            json.put("token", token);
            return NewResponseUtil.newSucceedResponse(json);
        }

        //3.判断用户是否在老系统中(老用户则需同步数据至当前系统)
        String customerId = UUIDUitl.generateLowerString(32);
        Integer state = syncOldSystemCustomerDataByUnionId(unionid, customerId, sessionKey);
        //强制用户进行手机号登录流程
        if (state.equals(0)) {
            //获取小程序授权数据并加密，返回至客户端
            WechatUserInfoBo wechatUserInfoBo = WechatUserInfoBo.builder()
                .type(LoginTypeContstants.PROGRAM_LOGIN).unionId(unionid).openId(openid)
                .nickname(nickname).sex(sex).headPicture(headPicture).sessionKey(sessionKey)
                .build();
            //返回数据
            String enc = AESEncryptUtil.encryptAES(JSON.toJSONString(wechatUserInfoBo));
            json.put("noPhone", 1);//需要绑定手机号
            json.put("wechatUserInfoBo", enc);
            return NewResponseUtil.newSucceedResponse(json);
        }

        //4.老系统同步数据至当前系统后，进行登录token相关逻辑
        String token = TokenUtil.generateToken(customerId);
        //插入 登录登出记录表
        LocalDateTime logoutTime = LoginTimeOutUtil.getLoginTimeOut();
        LoginLogoutRecordPo loginLogoutRecordPo = new LoginLogoutRecordPo();
        loginLogoutRecordPo.setCustomerId(customerId);
        loginLogoutRecordPo.setToken(token);
        loginLogoutRecordPo.setLoginTime(LocalDateTime.now());
        loginLogoutRecordPo.setLogoutTime(logoutTime);
        loginLogoutRecordService.addLoginLogoutRecord(loginLogoutRecordPo);
        //返回数据
        json.put("token", token);
        return NewResponseUtil.newSucceedResponse(json);
    }

    @Override
    public NewResponseUtil accessUnionIdBindPhone(
        ApiUnionIdProgramBindPhoneFacade unionIdProgramFacade) {
        //1.获取小程序授权数据
        WechatUserInfoBo wechatUserInfoBo = JSON
            .parseObject(unionIdProgramFacade.getWechatUserInfoBo(), WechatUserInfoBo.class);
        String sessionKey = wechatUserInfoBo.getSessionKey();
        String result = null;
        try {
            result = AESUtil
                .decrypt(Base64.decodeBase64(unionIdProgramFacade.getEncryptedData()),
                    Base64.decodeBase64(sessionKey),
                    Base64.decodeBase64(unionIdProgramFacade.getIv()));
            log.info("微信绑定手机号解密数据:{}", result);
        } catch (Exception e) {
            log.error("数据解密失败", e);
            return NewResponseUtil.newFailureResponse("数据解密失败!");
        }
        if (null == result) {
            return NewResponseUtil.newFailureResponse("数据解密失败!");
        }
        JSONObject js = JSONObject.parseObject(result);
        String phoneNumber = js.getString("phoneNumber");
        log.info("微信绑定手机号:{}", phoneNumber);
        //返回数据
        JSONObject resjson = new JSONObject();
        resjson.put("phoneNumber", phoneNumber);
        return NewResponseUtil.newSucceedResponse(resjson);
    }

    @Override
    public NewResponseUtil verifyPhone(ApiVerifyPhoneDataFacade facade) {
        String phone = facade.getPhoneData();
        CustomerBasicInfoPo customerBasicInfoPo = customerBasicInfoService
            .getCustomerBasicInfoByPhone(phone);
        //isNew:1新用户 2老用户
        JSONObject resjson = new JSONObject();
        resjson.put("isNew", customerBasicInfoPo == null ? 1 : 2);
        return NewResponseUtil.newSucceedResponse(resjson);
    }

}
