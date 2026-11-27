package com.cosmetic.scm.domain.rules;

import java.util.Collection;

/**
 * 委托方提供物料（《化妆品生产质量管理规范》第五十四条第二款；检查要点委托生产版第 12 项）：
 * 向受托方提供原料、包材的，要向受托方提供物料验收标准、明确原料成分。
 * 这里判断一家受托厂对一个品牌供料物料的状态：有没有验收标准、受托厂确认的是不是要求的版本。不依赖框架，有单元测试。
 */
public final class SupplySpecRule {

    public enum State {
        /** 物料还没有生效的质量标准，没有可提供的验收标准。 */
        NO_SPEC("没有验收标准"),
        /** 有标准，受托厂一版都没确认过。 */
        UNACKED("待受托厂确认"),
        /** 受托厂确认的是旧版，要求的版本还没确认。 */
        OUTDATED("确认的是旧版"),
        ACKED("已确认");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** 有标准却没确认到要求的版本：要提醒受托厂确认。 */
        public boolean awaitingAck() {
            return this == UNACKED || this == OUTDATED;
        }
    }

    private SupplySpecRule() {
    }

    /**
     * requiredVersion：要求受托厂确认的版本（现行版本，或自查期间结束时生效的版本），空表示没有标准；
     * ackedVersions：这家受托厂确认过的这个物料的标准版本。确认了要求的版本或更新的版本都算已确认。
     */
    public static State state(Integer requiredVersion, Collection<Integer> ackedVersions) {
        if (requiredVersion == null) {
            return State.NO_SPEC;
        }
        if (ackedVersions == null || ackedVersions.isEmpty()) {
            return State.UNACKED;
        }
        return ackedVersions.stream().anyMatch(v -> v != null && v >= requiredVersion) ? State.ACKED : State.OUTDATED;
    }

    /** 原料要明确成分：至少登记 INCI 名称。包材不要求。 */
    public static boolean compositionMissing(boolean rawMaterial, String inci) {
        return rawMaterial && (inci == null || inci.isBlank());
    }
}
