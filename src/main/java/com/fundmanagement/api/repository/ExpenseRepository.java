package com.fundmanagement.api.repository;
import com.fundmanagement.api.domain.ExpenseDocument;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
public interface ExpenseRepository extends MongoRepository<ExpenseDocument, String> { List<ExpenseDocument> findByDeletedFalseAndDateBetweenOrderByDateDesc(LocalDate from, LocalDate to); List<ExpenseDocument> findByDeletedFalseAndDateBetweenAndCategoryOrderByDateDesc(LocalDate from, LocalDate to, String category); }
