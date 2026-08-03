package io.github.limuqy.mc.hassium.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 运行时映射无关的反射字段查找。
 * <p>
 * 不同加载器的生产运行时使用不同映射：NeoForge 1.20.2+ 为 mojmap，
 * Forge 全线（1.20.1/1.20.6/1.21.x）与 1.20.1 的 neoforge 子项目（loom.platform=forge）为 SRG，
 * Fabric 全线为 intermediary。按字段名字符串反射（如 {@code "channel"}）在
 * SRG / intermediary 环境必然 NoSuchFieldException；loom 开发环境（named 映射）却一切正常，
 * 因此冒烟测试无法暴露。本类全部按「字段类型 / 结构特征」匹配，与映射名无关。
 */
public final class ReflectionCompat {
    private ReflectionCompat() {}

    /**
     * 沿继承链（含自身）查找第一个声明类型为 {@code fieldType} 的实例字段。
     *
     * @throws NoSuchFieldException 链上无匹配字段
     */
    public static Field findFieldByType(Class<?> clazz, Class<?> fieldType, boolean traverseSuperclasses)
            throws NoSuchFieldException {
        Class<?> cursor = clazz;
        while (cursor != null && cursor != Object.class) {
            for (Field f : cursor.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == fieldType) {
                    return f;
                }
            }
            if (!traverseSuperclasses) {
                break;
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException(
                fieldType.getName() + " not found on " + clazz.getName() + " hierarchy");
    }

    /**
     * 取对象上第一个声明类型为 {@code fieldType} 的实例字段值。
     */
    public static Object getFieldByType(Object target, Class<?> fieldType, boolean traverseSuperclasses)
            throws ReflectiveOperationException {
        Field field = findFieldByType(target.getClass(), fieldType, traverseSuperclasses);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 查找类中唯一的「成员类类型」实例字段。
     * <p>
     * 用于 {@code ClientChunkCache.storage}（类型为私有内部类 {@code ClientChunkCache$Storage}，
     * 无法按类型引用——内部类名本身也会被混淆）。该类中成员类类型字段唯一，特征与映射名无关。
     *
     * @throws NoSuchFieldException 无成员类类型字段
     */
    public static Field findMemberClassField(Class<?> clazz) throws NoSuchFieldException {
        for (Field f : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().isMemberClass()) {
                return f;
            }
        }
        throw new NoSuchFieldException("no member-class field on " + clazz.getName());
    }
}
