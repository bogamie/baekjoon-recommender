package com.baekjoonrec.problem;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "problems")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Problem {

    @Id
    private Integer id;

    @Column(length = 500)
    private String title;

    private Integer level;

    @Column(name = "solved_count")
    private Integer solvedCount;

    @Column(name = "accepted_rate")
    private Double acceptedRate;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProblemTag> tags = new ArrayList<>();

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
