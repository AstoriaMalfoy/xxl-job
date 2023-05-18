package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.model.XxlJobGroup;
import com.xxl.job.admin.core.model.XxlJobRegistry;
import com.xxl.job.core.biz.model.RegistryParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.enums.RegistryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.*;

/**
 * job registry instance
 * @author xuxueli 2016-10-02 19:10:24
 */
public class JobRegistryHelper {
	private static Logger logger = LoggerFactory.getLogger(JobRegistryHelper.class);

	private static JobRegistryHelper instance = new JobRegistryHelper();
	public static JobRegistryHelper getInstance(){
		return instance;
	}

	// 注册线程池，用于异步注册和注销
	private ThreadPoolExecutor registryOrRemoveThreadPool = null;
	private Thread registryMonitorThread;
	private volatile boolean toStop = false;

	public void start(){

		// for registry or remove
		registryOrRemoveThreadPool = new ThreadPoolExecutor(
				2,
				10,
				30L,
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>(2000),
				r -> new Thread(r, "xxl-job, admin JobRegistryMonitorHelper-registryOrRemoveThreadPool-" + r.hashCode()),
				(r, executor) -> {
					r.run();
					logger.warn(">>>>>>>>>>> xxl-job, registry or remove too fast, match threadpool rejected handler(run now).");
				});

		// for monitor
		/**
		 * 这个线程的主要作用是维护执行器组的注册信息
		 * 1.获取所有的执行器组
		 * 2.获取所有的执行器注册信息
		 * 3.将执行器注册信息按照appname分组
		 * 4.将执行器注册信息中的地址信息覆盖原有的执行器组中的数据
		 *
		 * 线程阻塞，30s执行一次
		 * 守护线程
		 */
		registryMonitorThread = new Thread(() -> {
			while (!toStop) {
				try {
					// auto registry group
					List<XxlJobGroup> groupList = XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().findByAddressType(0);
					if (groupList!=null && !groupList.isEmpty()) {

						// remove dead address (admin/executor) 删除已经失效的地址 （靠JobReg中的updateTime字段来判断）
						List<Integer> ids = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().findDead(RegistryConfig.DEAD_TIMEOUT, new Date());
						if (ids!=null && ids.size()>0) {
							XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().removeDead(ids);
						}

						// fresh online address (admin/executor) 获取所有存活的地址
						HashMap<String, List<String>> appAddressMap = new HashMap<String, List<String>>();
						List<XxlJobRegistry> list = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().findAll(RegistryConfig.DEAD_TIMEOUT, new Date());
						if (list != null) {
							for (XxlJobRegistry item: list) {
								// 处理执行节点 执行节点按照appname分组
								if (RegistryConfig.RegistType.EXECUTOR.name().equals(item.getRegistryGroup())) {
									String appname = item.getRegistryKey();
									List<String> registryList = appAddressMap.get(appname);
									if (registryList == null) {
										registryList = new ArrayList<String>();
									}

									if (!registryList.contains(item.getRegistryValue())) {
										registryList.add(item.getRegistryValue());
									}
									appAddressMap.put(appname, registryList);
								}
							}
						}

						// fresh group address
						for (XxlJobGroup group: groupList) {
							List<String> registryList = appAddressMap.get(group.getAppname());
							String addressListStr = null;
							if (registryList!=null && !registryList.isEmpty()) {
								Collections.sort(registryList);
								StringBuilder addressListSB = new StringBuilder();
								for (String item:registryList) {
									addressListSB.append(item).append(",");
								}
								addressListStr = addressListSB.toString();
								addressListStr = addressListStr.substring(0, addressListStr.length()-1);
							}
							group.setAddressList(addressListStr);
							group.setUpdateTime(new Date());

							XxlJobAdminConfig.getAdminConfig().getXxlJobGroupDao().update(group);
						}
					}
				} catch (Exception e) {
					if (!toStop) {
						logger.error(">>>>>>>>>>> xxl-job, job registry monitor thread error:{}", e);
					}
				}
				try {
					TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
				} catch (InterruptedException e) {
					if (!toStop) {
						logger.error(">>>>>>>>>>> xxl-job, job registry monitor thread error:{}", e);
					}
				}
			}
			logger.info(">>>>>>>>>>> xxl-job, job registry monitor thread stop");
		});
		registryMonitorThread.setDaemon(true);
		registryMonitorThread.setName("xxl-job, admin JobRegistryMonitorHelper-registryMonitorThread");
		registryMonitorThread.start();
	}

	public void toStop(){
		toStop = true;

		// stop registryOrRemoveThreadPool
		registryOrRemoveThreadPool.shutdownNow();

		// stop monitir (interrupt and wait)
		// todo 中断声明有效吗？？？？？
		registryMonitorThread.interrupt();
		try {
			registryMonitorThread.join();
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
		}
	}


	// ---------------------- helper ----------------------

	// todo 注册之后，没有立即同步到group中。也就是说，可能会存在，节点已经下线，并且也已经调用了remove方法，但是group中还是存在这个节点的情况
	public ReturnT<String> registry(RegistryParam registryParam) {

		// valid
		if (!StringUtils.hasText(registryParam.getRegistryGroup())
				|| !StringUtils.hasText(registryParam.getRegistryKey())
				|| !StringUtils.hasText(registryParam.getRegistryValue())) {
			return new ReturnT<String>(ReturnT.FAIL_CODE, "Illegal Argument.");
		}

		// async execute
		registryOrRemoveThreadPool.execute(new Runnable() {
			@Override
			public void run() {
				int ret = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().registryUpdate(registryParam.getRegistryGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue(), new Date());
				if (ret < 1) {
					XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().registrySave(registryParam.getRegistryGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue(), new Date());

					// fresh
					freshGroupRegistryInfo(registryParam);
				}
			}
		});

		return ReturnT.SUCCESS;
	}

	public ReturnT<String> registryRemove(RegistryParam registryParam) {

		// valid
		if (!StringUtils.hasText(registryParam.getRegistryGroup())
				|| !StringUtils.hasText(registryParam.getRegistryKey())
				|| !StringUtils.hasText(registryParam.getRegistryValue())) {
			return new ReturnT<String>(ReturnT.FAIL_CODE, "Illegal Argument.");
		}

		// async execute
		registryOrRemoveThreadPool.execute(new Runnable() {
			@Override
			public void run() {
				int ret = XxlJobAdminConfig.getAdminConfig().getXxlJobRegistryDao().registryDelete(registryParam.getRegistryGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue());
				if (ret > 0) {
					// fresh
					freshGroupRegistryInfo(registryParam);
				}
			}
		});

		return ReturnT.SUCCESS;
	}

	private void freshGroupRegistryInfo(RegistryParam registryParam){
		// Under consideration, prevent affecting core tables
	}


}
