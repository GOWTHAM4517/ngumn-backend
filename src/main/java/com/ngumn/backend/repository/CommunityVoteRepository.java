package com.ngumn.backend.repository;

import com.ngumn.backend.entity.CommunityVote;
import com.ngumn.backend.entity.VoteTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityVoteRepository extends JpaRepository<CommunityVote, Long> {
    boolean existsByTargetTypeAndTargetIdAndVoterId(VoteTarget targetType, Long targetId, Long voterId);
    List<CommunityVote> findByTargetTypeAndTargetId(VoteTarget targetType, Long targetId);
    List<CommunityVote> findByVoterIdAndTargetType(Long voterId, VoteTarget targetType);
    long countByTargetTypeAndTargetIdAndAgree(VoteTarget targetType, Long targetId, Boolean agree);
    long countByVoterId(Long voterId);
    long countByVoterIdAndOutcomeMatchedTrue(Long voterId);
    long countByVoterIdAndOutcomeMatchedFalse(Long voterId);
}
