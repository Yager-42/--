package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.Class2ProjectionAdvanceResult;

public interface IClass2CounterProjectionStateRepository {

    Class2ProjectionAdvanceResult advanceIfNewer(String projectionKey, String projectionType, long projectionVersion);
}

