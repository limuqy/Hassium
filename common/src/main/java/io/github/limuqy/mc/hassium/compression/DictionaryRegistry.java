package io.github.limuqy.mc.hassium.compression;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * 字典注册表接口
 * <p>
 * 负责管理 ZSTD 字典的加载、查找和校验。
 */
public interface DictionaryRegistry {

    /**
     * 注册字典
     *
     * @param descriptor 字典描述符
     * @param data       字典数据
     * @throws CompressionException 注册失败
     */
    void register(DictionaryDescriptor descriptor, byte[] data) throws CompressionException;

    /**
     * 查找字典
     *
     * @param dictionaryId 字典 ID
     * @return 字典数据，如果不存在则返回空
     */
    Optional<byte[]> findDictionary(String dictionaryId);

    /**
     * 获取字典描述符
     *
     * @param dictionaryId 字典 ID
     * @return 字典描述符，如果不存在则返回空
     */
    Optional<DictionaryDescriptor> getDescriptor(String dictionaryId);

    /**
     * 检查字典是否存在
     *
     * @param dictionaryId 字典 ID
     * @return 如果存在返回 true
     */
    boolean hasDictionary(String dictionaryId);

    /**
     * 校验字典
     *
     * @param dictionaryId 字典 ID
     * @return 如果校验通过返回 true
     * @throws CompressionException 校验过程异常
     */
    boolean verify(String dictionaryId) throws CompressionException;

    /**
     * 获取所有已注册的字典
     */
    Collection<DictionaryDescriptor> getAllDescriptors();

    /**
     * 从目录加载字典
     *
     * @param directory 字典目录
     * @throws CompressionException 加载失败
     */
    void loadFromDirectory(Path directory) throws CompressionException;

    /**
     * 卸载字典
     *
     * @param dictionaryId 字典 ID
     * @return 如果成功卸载返回 true
     */
    boolean unregister(String dictionaryId);
}
