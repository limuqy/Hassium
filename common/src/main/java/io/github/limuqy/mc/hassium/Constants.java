package io.github.limuqy.mc.hassium;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "hassium";
	public static final String MOD_NAME = "Hassium";
	public static final String MOD_VERSION = "1.0.0";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

	/**
	 * 网络通道命名空间
	 */
	public static final String NETWORK_NAMESPACE = "hassium";

	/**
	 * 缓存目录名
	 */
	public static final String CACHE_DIR_NAME = "hassium-cache";

	/**
	 * 当前存储格式版本
	 */
	public static final int CURRENT_STORAGE_FORMAT_VERSION = 1;

	/**
	 * 当前协议版本
	 */
	public static final int CURRENT_PROTOCOL_VERSION = 1;

	/**
	 * 网络自定义通道压缩算法（当前仅实现 ZSTD，不开放配置）
	 */
	public static final String NETWORK_COMPRESSION_ALGORITHM = "hassium:zstd";

	/**
	 * 内置 ZSTD 字典 ID（当前仅此一份，不开放配置）
	 */
	public static final String DEFAULT_ZSTD_DICTIONARY_ID = "hassium-dictionary";

	/**
	 * ConfigSpec 文件名（相对 {@code config/}，写入 {@code config/hassium/}）
	 */
	public static final String CONFIG_CLIENT_FILE = "hassium/hassium-client.toml";
	public static final String CONFIG_SERVER_FILE = "hassium/hassium-server.toml";
}