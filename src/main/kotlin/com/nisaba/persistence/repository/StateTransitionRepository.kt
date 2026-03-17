package com.nisaba.persistence.repository

import com.nisaba.persistence.entity.StateTransitionEntity
import org.springframework.data.repository.CrudRepository

interface StateTransitionRepository : CrudRepository<StateTransitionEntity, Long> {
    fun findByInfohashOrderByOccurredAtDesc(infohash: String): List<StateTransitionEntity>
}
