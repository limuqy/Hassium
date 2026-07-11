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
}