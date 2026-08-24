package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.MemberDocument;
import com.fundmanagement.api.domain.MemberStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface MemberRepository extends MongoRepository<MemberDocument, String> {
  List<MemberDocument> findByStatus(MemberStatus status);
  List<MemberDocument> findByStatusAndNormalizedFullName(MemberStatus status, String normalizedFullName);
  List<MemberDocument> findByStatusAndMemberCode(MemberStatus status, String memberCode);
  Page<MemberDocument> findByStatusNot(MemberStatus status, Pageable pageable);
}
