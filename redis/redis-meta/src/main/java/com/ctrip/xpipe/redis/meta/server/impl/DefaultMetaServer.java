package com.ctrip.xpipe.redis.meta.server.impl;



import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Resource;

import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.recipes.locks.LockInternals;
import org.apache.curator.framework.recipes.locks.LockInternalsSorter;
import org.apache.curator.framework.recipes.locks.StandardLockInternalsDriver;
import org.apache.zookeeper.WatchedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unidal.tuple.Pair;

import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.api.observer.Observer;
import com.ctrip.xpipe.api.pool.SimpleKeyedObjectPool;
import com.ctrip.xpipe.command.CommandExecutionException;
import com.ctrip.xpipe.netty.commands.NettyClient;
import com.ctrip.xpipe.observer.AbstractLifecycleObservable;
import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.entity.ShardMeta;
import com.ctrip.xpipe.redis.core.entity.XpipeMeta;
import com.ctrip.xpipe.redis.core.meta.MetaZkConfig;
import com.ctrip.xpipe.redis.core.meta.ShardStatus;
import com.ctrip.xpipe.redis.meta.server.MetaHolder;
import com.ctrip.xpipe.redis.meta.server.MetaServer;
import com.ctrip.xpipe.redis.meta.server.config.MetaServerConfig;
import com.ctrip.xpipe.redis.meta.server.exception.RedisMetaServerException;
import com.ctrip.xpipe.redis.meta.server.impl.event.ActiveKeeperChanged;
import com.ctrip.xpipe.redis.meta.server.impl.event.RedisMasterChanged;
import com.ctrip.xpipe.redis.meta.server.job.SlavePromotionJob;
import com.ctrip.xpipe.utils.MapUtils;
import com.ctrip.xpipe.utils.ObjectUtils;
import com.ctrip.xpipe.utils.ObjectUtils.EqualFunction;
import com.ctrip.xpipe.zk.ZkClient;
import com.ctrip.xpipe.utils.ServicesUtil;

/**
 * @author marsqing
 *
 *         May 25, 2016 5:24:27 PM
 */
@Component
public class DefaultMetaServer extends AbstractLifecycleObservable implements MetaServer, Observer {

	@Autowired
	private MetaHolder metaHolder;
	
	@Resource( name = "clientPool" )
	SimpleKeyedObjectPool<InetSocketAddress, NettyClient> clientPool;

	@SuppressWarnings("unused")
	@Autowired
	private MetaServerConfig config;

	@Autowired
	private ZkClient zkClient;
	
	@Autowired
	private List<MetaChangeListener>  metaChangeListeners;

	private ConcurrentMap<Pair<String, String>, ShardStatus> shardStatuses = new ConcurrentHashMap<>();

	private String currentDc;
	
	@Override
	protected void doInitialize() throws Exception {
		metaHolder.initialize();
		zkClient.initialize();
		currentDc = ServicesUtil.getFoundationService().getDataCenter();
	}

	@Override
	protected void doStart() throws Exception {
		
		zkClient.start();
		metaHolder.start();
		metaHolder.addObserver(this);
		
		
		for(MetaChangeListener metaChangeListener : metaChangeListeners){
			logger.info("[doStart][addObserver]{}", metaChangeListener);
			addObserver(metaChangeListener);
		}
		
		loadFromXpipeMeta(metaHolder.getMeta());

		// TODO watch and update cluster from zk
		for (ClusterMeta cluster : metaHolder.getMeta().findDc(currentDc).getClusters().values()) {
			watchCluster(cluster);
		}
	}
	
	private void loadFromXpipeMeta(XpipeMeta meta) {
		
		logger.debug("[loadFromXpipeMeta]{}", meta);
		
		Map<String, ClusterMeta> clusters = metaHolder.getMeta().findDc(currentDc).getClusters();

		for (ClusterMeta cluster : clusters.values()) {
			for (ShardMeta shard : cluster.getShards().values()) {
				ShardStatus shardStatus = MapUtils.getOrCreate(shardStatuses, new Pair<>(cluster.getId(), shard.getId()),  ShardStatus.getFactory());
				
				updateRedisMaster(cluster.getId(), shard, cluster, shardStatus);
				updateUpstreamKeeper(cluster.getId(), shard.getId(), shardStatus);
			}
		}
	}

	@Override
	protected void doStop() throws Exception {
		
		for(MetaChangeListener metaChangeListener : metaChangeListeners){
			removeObserver(metaChangeListener);
		}
		metaHolder.removeObserver(this);
		metaHolder.stop();
		zkClient.stop();
	}
	
	@Override
	protected void doDispose() throws Exception {
		
		metaHolder.dispose();;
		zkClient.dispose();;
	}

	@Override
	public KeeperMeta getActiveKeeper(String clusterId, String shardId) {
		ShardStatus status = shardStatuses.get(new Pair<>(clusterId, shardId));
		return status == null ? null : status.getActiveKeeper();
	}

	@Override
	public RedisMeta getRedisMaster(String clusterId, String shardId) {
		ShardStatus status = shardStatuses.get(new Pair<>(clusterId, shardId));
		return status == null ? null : status.getRedisMaster();
	}
	
	public void updateUpstreamKeeper(String clusterId, String shardId, KeeperMeta keeperMeta){
		
		ShardStatus status = shardStatuses.get(new Pair<>(clusterId, shardId));
		status.setUpstreamKeeper(keeperMeta);
	}
	
	public void updateRedisMaster(String clusterId, String shardId, RedisMeta redisMeta){
		
		ShardStatus status = shardStatuses.get(new Pair<>(clusterId, shardId));
		status.setRedisMaster(redisMeta);
	}

	@Override
	public KeeperMeta getUpstreamKeeper(String clusterId, String shardId) {
		ShardStatus status = shardStatuses.get(new Pair<>(clusterId, shardId));
		return status == null ? null : status.getUpstreamKeeper();
	}

	@Override
	public void watchCluster(ClusterMeta cluster) throws Exception {
		observeLeader(cluster);
	}

	private void updateRedisMaster(String clusterId, ShardMeta shard, ClusterMeta cluster, ShardStatus shardStatus) {
		
		String activeDc = cluster.getActiveDc();
		if(activeDc.equals(currentDc)){
			for (RedisMeta redis : shard.getRedises()) {
				if (redis.isMaster()) {
					shardStatus.setRedisMaster(redis);
				}
			}
		}else{
			if(shardStatus.getRedisMaster() != null){
				logger.info("[updateRedisMaster][change redis master null][not active dc]{}", shardStatus.getRedisMaster());
			}
			shardStatus.setRedisMaster(null);
		}
	}

	private void updateUpstreamKeeper(String clusterId, String shardId, ShardStatus shardStatus) {

		XpipeMeta meta = metaHolder.getMeta();

		ShardMeta activeShard = null;
		for (DcMeta dc : meta.getDcs().values()) {
			if (currentDc.equals(dc.getId())) {
				// upstream keeper should locate in another dc
				continue;
			}

			ClusterMeta cluster = dc.findCluster(clusterId);
			if (cluster != null && dc.getId().equals(cluster.getActiveDc())) {
				ShardMeta shard = cluster.findShard(shardId);
				if (shard != null) {
					activeShard = shard;
					break;
				}
			}
		}

		KeeperMeta upstreamKeeper = null;
		if (activeShard != null) {
			for (KeeperMeta keeper : activeShard.getKeepers()) {
				if (keeper.isActive()) {
					upstreamKeeper = keeper;
					break;
				}
			}
		}

		shardStatus.setUpstreamKeeper(upstreamKeeper);
	}

	private void observeLeader(final ClusterMeta cluster) throws Exception {

		for (final ShardMeta shard : cluster.getShards().values()) {
			
			final String leaderLatchPath = MetaZkConfig.getKeeperLeaderLatchPath(cluster.getId(), shard.getId());

			List<String> children = zkClient.get().getChildren().usingWatcher(new CuratorWatcher() {

				@Override
				public void process(WatchedEvent event) throws Exception {
					
					logger.info("[process]" + event);
					updateShardLeader(zkClient.get().getChildren().usingWatcher(this).forPath(leaderLatchPath), leaderLatchPath, cluster.getId(),
							shard.getId());
				}
			}).forPath(leaderLatchPath);

			updateShardLeader(children, leaderLatchPath, cluster.getId(), shard.getId());
		}
	}

	private LockInternalsSorter sorter = new LockInternalsSorter() {
		@Override
		public String fixForSorting(String str, String lockName) {
			return StandardLockInternalsDriver.standardFixForSorting(str, lockName);
		}
	};

	private void updateShardLeader(List<String> children, String leaderLatchPath, String clusterId, String shardId) throws Exception {
		KeeperMeta keeper = null;

		if (children != null && !children.isEmpty()) {
			List<String> sortedChildren = LockInternals.getSortedChildren("latch-", sorter, children);
			String leaderId = new String(zkClient.get().getData().forPath(leaderLatchPath + "/" + sortedChildren.get(0)));

			keeper = new KeeperMeta();
			keeper.setActive(true);
			// TODO
			String[] parts = leaderId.split(":");
			if (parts.length != 3) {
				throw new RuntimeException("Error leader data in zk: " + leaderId);
			}
			keeper.setIp(parts[0]);
			keeper.setPort(Integer.parseInt(parts[1]));
			keeper.setId(parts[2]);
		}

		Pair<String, String> key = new Pair<>(clusterId, shardId);
		ShardStatus shardStatus = MapUtils.getOrCreate(shardStatuses, key, ShardStatus.getFactory());
		
		
		KeeperMeta oldActiveKeeper = shardStatus.getActiveKeeper();
		
		if(keeperChanged(oldActiveKeeper, keeper)){
			notifyObservers(new ActiveKeeperChanged(clusterId, shardId, oldActiveKeeper, keeper));
			if (keeper != null) {
				logger.info("{} become active keeper of cluster:{} shard:{}", keeper, clusterId, shardId);
				shardStatus.setActiveKeeper(keeper);
			} else {
				logger.info("all keeper of cluster:{} shard:{} is down", clusterId, shardId);
				shardStatus.setActiveKeeper(null);
			}
		}
	}

	private boolean keeperChanged(KeeperMeta oldActiveKeeper, KeeperMeta keeper) {
		
		return !ObjectUtils.equals(oldActiveKeeper, keeper, new EqualFunction<KeeperMeta>() {

			@Override
			public boolean equals(KeeperMeta obj1, KeeperMeta obj2) {
				
				return ObjectUtils.equals(obj1.getIp(), obj2.getIp()) && ObjectUtils.equals(obj1.getPort(), obj2.getPort());
			}
			
		});
	}

	@Override
	public void update(Object args, Observable observable) {
		
		if(args instanceof XpipeMeta){
			logger.debug("[update][load from xpipe meta]");
			XpipeMeta xpipeMeta = (XpipeMeta) args;
			loadFromXpipeMeta(xpipeMeta);
		}
		
	}

	public void setConfig(MetaServerConfig config) {
		this.config = config;
	}

	public void setZkClient(ZkClient zkClient) {
		this.zkClient = zkClient;
	}
	
	public void setMetaHolder(MetaHolder metaHolder) {
		this.metaHolder = metaHolder;
	}
	
	public void setMetaChangeListeners(List<MetaChangeListener> metaChangeListeners) {
		this.metaChangeListeners = metaChangeListeners;
	}

	@Override
	public void promoteRedisMaster(String clusterId, String shardId, String promoteIp, int promotePort) throws InterruptedException, RedisMetaServerException, ExecutionException, CommandExecutionException {
		
		if(!currentDc.equals(getActiveDc(clusterId))){
			throw new IllegalStateException("currrent dc not active, can not promote master");
		}

		logger.info("[promteRedisMaster]{}-{},{}:{}", clusterId, shardId, promoteIp, promotePort);
		KeeperMeta activeKeeper = getActiveKeeper(clusterId, shardId); 
		if(activeKeeper == null){
			throw new RedisMetaServerException("[promoteRedisMaster][active keeper not found!]" + clusterId + "," + shardId + "," + promoteIp + ":" + promotePort);
		}
		
		RedisMeta oldRedisMaster = getRedisMaster(clusterId, shardId);
		
		new SlavePromotionJob(activeKeeper, promoteIp, promotePort, clientPool).execute().sync();
		
		RedisMeta redisMeta = new RedisMeta();
		redisMeta.setIp(promoteIp);
		redisMeta.setPort(promotePort);
		redisMeta.setMaster(true);
		updateRedisMaster(clusterId, shardId, redisMeta);
		
		notifyObservers(new RedisMasterChanged(clusterId, shardId, oldRedisMaster, redisMeta));
		
	}

	private String getActiveDc(String clusterId) {
		
		XpipeMeta xpipeMeta = metaHolder.getMeta();
		for(DcMeta dcMeta : xpipeMeta.getDcs().values()){
			ClusterMeta clusterMeta = dcMeta.getClusters().get(clusterId);
			if(clusterMeta == null){
				continue;
			}
			return clusterMeta.getActiveDc();
		}
		throw new IllegalStateException("unfound cluster:" + clusterId);
	}
}
