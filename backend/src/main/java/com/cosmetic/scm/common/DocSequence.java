package com.cosmetic.scm.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "doc_sequence")
@Getter
@Setter
@NoArgsConstructor
public class DocSequence {

    @Id
    @Column(name = "seq_key")
    private String seqKey;

    @Column(name = "seq_value", nullable = false)
    private int seqValue;

    public DocSequence(String seqKey, int seqValue) {
        this.seqKey = seqKey;
        this.seqValue = seqValue;
    }
}
