package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.DuePeriodDocument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
public interface DuePeriodRepository extends MongoRepository<DuePeriodDocument, String> {
  Optional<DuePeriodDocument> findByMemberIdAndMonth(String memberId, String month);
  List<DuePeriodDocument> findByMemberIdOrderByMonthAsc(String memberId);
  List<DuePeriodDocument> findByMemberIdInOrderByMonthAsc(Collection<String> memberIds);
  @Query(value = "{ 'memberId': { $in: ?0 }, 'month': { $gte: ?1, $lte: ?2 } }", fields = "{ '_id': 1, 'memberId': 1, 'month': 1, 'amountDue': 1, 'amountPaid': 1, 'status': 1 }")
  List<DuePeriodDocument> findBoardByMemberIdInAndMonthBetweenOrderByMonthAsc(Collection<String> memberIds, String from, String to);
  List<DuePeriodDocument> findByMemberIdAndIdIn(String memberId, Collection<String> ids);
  @Query(value = "{ 'memberId': ?0 }", fields = "{ '_id': 1, 'memberId': 1, 'month': 1, 'amountDue': 1, 'amountPaid': 1, 'status': 1 }")
  List<DuePeriodDocument> findPublicByMemberIdOrderByMonthAsc(String memberId);
  List<DuePeriodDocument> findByMonth(String month);
  Page<DuePeriodDocument> findByMonth(String month, Pageable pageable);
  Page<DuePeriodDocument> findByMemberId(String memberId, Pageable pageable);
  Page<DuePeriodDocument> findAllBy(Pageable pageable);
}
