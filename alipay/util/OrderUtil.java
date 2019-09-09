package tk.mybatis.springboot.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

public class OrderUtil {

    //6位随机数
    private final static long w = 100000;

    public static String getOrderNo() {
        SimpleDateFormat sf = new SimpleDateFormat("yyyyMMddHHmmss");
        String temp = sf.format(new Date());
        long random = (long) ((Math.random() + 1) * w);
        return temp + random;
    }

}
