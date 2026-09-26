package com.ngumn.backend.service;

import com.ngumn.backend.entity.Reward;
import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.repository.RewardRepository;
import com.ngumn.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RewardService {

    private final RewardRepository rewardRepository;
    private final UserRepository userRepository;

    public RewardService(RewardRepository rewardRepository, UserRepository userRepository) {
        this.rewardRepository = rewardRepository;
        this.userRepository = userRepository;
    }

    public Reward grant(User user, int points, String reason, RoadReport roadReport) {
        Reward reward = Reward.builder()
                .user(user)
                .points(points)
                .reason(reason)
                .roadReport(roadReport)
                .build();
        reward = rewardRepository.save(reward);

        user.setRewardPoints((user.getRewardPoints() != null ? user.getRewardPoints() : 0) + points);
        userRepository.save(user);

        return reward;
    }

    public List<Reward> forUser(Long userId) {
        return rewardRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
