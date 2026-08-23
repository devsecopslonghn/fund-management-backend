package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface MemberRepository extends MongoRepository<MemberDocument, String> {
  Optional<MemberDocument> findByMagicTokenHash(String magicTokenHash);
  List<MemberDocument> findByStatus(MemberStatus status);
  Page<MemberDocument> findByStatusNot(MemberStatus status, Pageable pageable);
}
