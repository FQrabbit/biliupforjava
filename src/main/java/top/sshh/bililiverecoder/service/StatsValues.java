package top.sshh.bililiverecoder.service;

import org.springframework.beans.BeanWrapperImpl;
import java.math.BigDecimal;
import java.util.Objects;

final class StatsValues {
    private StatsValues() {}

    static boolean same(Object left, Object right, String... ignored) {
        if (left == null || right == null) return left == right;
        var a = new BeanWrapperImpl(left);
        var b = new BeanWrapperImpl(right);
        var exclusions = java.util.Set.of(ignored);
        for (var property : a.getPropertyDescriptors()) {
            String name = property.getName();
            if (name.equals("class") || exclusions.contains(name)) continue;
            Object x = a.getPropertyValue(name), y = b.getPropertyValue(name);
            if (x instanceof BigDecimal dx && y instanceof BigDecimal dy) {
                if (dx.compareTo(dy) != 0) return false;
            } else if (!Objects.equals(x, y)) return false;
        }
        return true;
    }
}
