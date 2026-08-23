package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.DuePeriodDocument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
public interface DuePeriodRepository extends MongoRepository<DuePeriodDocument, String> {
  Optional<DuePeriodDocument> findByMemberIdAndMonth(String memberId, String month);
  List<DuePeriodDocument> findByMemberIdOrderByMonthAsc(String memberId);
  List<DuePeriodDocument> findByMemberIdAndIdIn(String memberId, Collection<String> ids);
  List<DuePeriodDocument> findByMonth(String month);
  Page<DuePeriodDocument> findByMonth(String month, Pageable pageable);
  Page<DuePeriodDocument> findByMemberId(String memberId, Pageable pageable);
  Page<DuePeriodDocument> findAllBy(Pageable pageable);
}
