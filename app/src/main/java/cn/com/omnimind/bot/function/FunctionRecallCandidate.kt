package cn.com.omnimind.bot.function

internal class FunctionRecallCandidate private constructor(
    val function: Map<String, Any?>,
    val score: Double,
    val source: String,
    val rank: Int,
) {
    val functionId: String
        get() = FunctionJson.firstNonBlank(function["function_id"])

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "function" to function,
        "retrieval" to linkedMapOf(
            "score" to score,
            "source" to source,
            "rank" to rank,
        ),
    )

    companion object {
        fun parse(raw: Any?): FunctionRecallCandidate {
            val candidate = FunctionJson.mapArg(raw)
            val function = FunctionJson.mapArg(candidate["function"])
            if (FunctionJson.firstNonBlank(function["function_id"]).isBlank()) {
                error("recall_candidate_function_id_required")
            }

            val retrieval = FunctionJson.mapArg(candidate["retrieval"])
            val score = (retrieval["score"] as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?: error("recall_candidate_score_invalid")
            val source = FunctionJson.firstNonBlank(retrieval["source"])
            if (source.isBlank()) error("recall_candidate_source_required")
            val rank = (retrieval["rank"] as? Number)?.toInt()
                ?.takeIf { it > 0 }
                ?: error("recall_candidate_rank_invalid")

            return FunctionRecallCandidate(
                function = function,
                score = score,
                source = source,
                rank = rank,
            )
        }
    }
}
