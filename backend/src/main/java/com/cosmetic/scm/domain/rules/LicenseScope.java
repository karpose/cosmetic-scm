package com.cosmetic.scm.domain.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产许可项目的特殊生产条件（《化妆品生产质量管理规范》第四十八条；检查要点委托生产版第 2 项）：
 * 生产眼部护肤类、儿童护肤类化妆品的，生产企业的许可项目要标注具备相应生产条件。
 * 成品属于哪一类、许可证上标注了什么都由使用者在主数据里登记，系统只做对照。不依赖框架，有单元测试。
 */
public final class LicenseScope {

    public static final String EYE_CARE = "眼部护肤类";
    public static final String CHILD_CARE = "儿童护肤类";

    private LicenseScope() {
    }

    /** 成品要求的生产条件，按“眼部护肤类、儿童护肤类”的顺序。 */
    public static List<String> required(boolean eyeCare, boolean childCare) {
        List<String> out = new ArrayList<>(2);
        if (eyeCare) {
            out.add(EYE_CARE);
        }
        if (childCare) {
            out.add(CHILD_CARE);
        }
        return out;
    }

    /** 成品要求、生产企业许可项目没有标注的生产条件；都有返回空。 */
    public static List<String> missing(boolean eyeCare, boolean childCare, boolean licenseEyeCare, boolean licenseChildCare) {
        List<String> out = new ArrayList<>(2);
        if (eyeCare && !licenseEyeCare) {
            out.add(EYE_CARE);
        }
        if (childCare && !licenseChildCare) {
            out.add(CHILD_CARE);
        }
        return out;
    }

    /** 一句提示：“某厂的生产许可项目没有标注具备眼部护肤类化妆品生产条件，不能<action>某成品”。 */
    public static String message(String factory, String product, List<String> missing, String action) {
        return factory + " 的生产许可项目没有标注具备" + String.join("、", missing) + "化妆品生产条件，不能" + action + " " + product
                + "。许可证上已有标注的，请在主数据里给这家单位勾上；成品类别标错的，请改成品主数据";
    }
}
