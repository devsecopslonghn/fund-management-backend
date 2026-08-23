package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.DuePeriodDocument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface DuePeriodRepository extends MongoRepository<DuePeriodDocument, String> {
  Optional<DuePeriodDocument> findByMemberIdAndMonth(String memberId, String month);
  List<DuePeriodDocument> findByMemberIdOrderByMonthAsc(String memberId);
  List<DuePeriodDocument> findByMemberIdAndIdIn(String memberId, Collection<String> ids);
  List<DuePeriodDocument> findByMonth(String month);
}
