package studio.oracle.data

import kotlinx.coroutines.flow.Flow
import studio.oracle.data.local.ExamPaperDao
import studio.oracle.data.local.ExamPaperEntity

class ExamRepository(
    private val dao: ExamPaperDao,
    private val workspaceId: String
) {
    val papers: Flow<List<ExamPaperEntity>> = dao.observePapers(workspaceId)

    suspend fun save(bundle: ExamBundle): ExamPaperEntity {
        val now = System.currentTimeMillis()
        val paper = bundle.paper
        val entity = ExamPaperEntity(
            id = paper.id,
            workspaceId = workspaceId,
            title = paper.title,
            subject = paper.subject,
            grade = paper.grade,
            durationMinutes = paper.durationMinutes,
            totalMarks = paper.totalMarks,
            paperJson = ExamPaperCodec.encodePaper(paper),
            answerKeyJson = ExamPaperCodec.encodeAnswerKey(bundle.answerKey),
            answersJson = "{}",
            status = "READY",
            score = null,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun saveAttempt(
        id: String,
        answers: Map<String, String>,
        status: String,
        score: Double? = null
    ): Boolean {
        require(status in setOf("READY", "IN_PROGRESS", "SUBMITTED"))
        return dao.updateAttempt(
            id = id,
            workspaceId = workspaceId,
            answersJson = ExamPaperCodec.encodeAnswers(answers),
            status = status,
            score = score,
            updatedAt = System.currentTimeMillis()
        ) == 1
    }

    suspend fun delete(id: String): Boolean = dao.delete(id, workspaceId) == 1
}