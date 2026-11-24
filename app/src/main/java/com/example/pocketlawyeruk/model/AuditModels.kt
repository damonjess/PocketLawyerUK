package com.example.pocketlawyeruk.model

import com.example.pocketlawyeruk.ui.screens.EncounterType
import java.util.UUID

enum class UkJurisdiction(val displayName: String) {
    ENGLAND_WALES("England & Wales"),
    SCOTLAND("Scotland"),
    NORTHERN_IRELAND("Northern Ireland")
}

enum class GowiselyStatus { VERIFIED, FLAGGED, SKIPPED }

enum class StatementVerdict(val label: String) {
    LAWFUL("Lawful"),
    UNLAWFUL_DEMAND("Unlawful"),
    MISLEADING_CLAIM("False / Myth")
}

enum class SpeakerSide(val label: String) {
    OFFICER("Police"),
    STAFF_SECURITY("Staff / Sec"),
    PUBLIC("Filmer / Public")
}

enum class ConfrontingParty(val label: String, val hint: String, val promptDirective: String) {
    AUTO(
        label = "Auto",
        hint = "AI detects police vs. private staff from audio cues",
        promptDirective = "Identify who is holding the camera/filming/flying a drone (ALWAYS speaker=\"Filmer\", side=\"PUBLIC\") separately from the confronting party (UK Police side=\"OFFICER\" or Private Staff/Security side=\"STAFF_SECURITY\")."
    ),
    POLICE(
        label = "Police",
        hint = "Locked to UK Police powers (PACE, s.50, RTA, NPCC)",
        promptDirective = "CONFIRMED BY USER: The confronting party is UK POLICE (side=\"OFFICER\", speaker=\"Officer\"), while the person filming/auditing/flying the drone is ALWAYS side=\"PUBLIC\" (speaker=\"Filmer\"). Keep their voices strictly separated — never label the filmer's quotes as Officer, and never label police as Staff."
    ),
    STAFF(
        label = "Staff",
        hint = "Locked to Private Staff / Security (Trespass & Civil Law)",
        promptDirective = "CONFIRMED BY USER: The confronting party is PRIVATE STAFF / SECURITY (side=\"STAFF_SECURITY\", speaker=\"Staff\" or \"Security\"), while the person holding the camera, filming, flying a drone, defending public photography rights, or mentioning their channel is ALWAYS side=\"PUBLIC\" (speaker=\"Filmer\"). Keep the Filmer and Staff voices strictly separated — never label the filmer's lines as Staff or Security. Mark all GOWISELY steps as \"SKIPPED\"."
    ),
    BOTH(
        label = "Both",
        hint = "Both UK Police and Private Staff/Security are present",
        promptDirective = "CONFIRMED BY USER: The person filming/auditing is ALWAYS side=\"PUBLIC\" (speaker=\"Filmer\"), while BOTH UK Police (side=\"OFFICER\") and Private Staff/Security (side=\"STAFF_SECURITY\") confront them. Keep all three roles strictly separated."
    )
}

data class GowiselyItem(
    val step: String,
    val status: GowiselyStatus,
    val note: String
)

data class TranscriptLine(
    val timestampSeconds: Int,
    val speaker: String,
    val text: String
)

data class AuditedStatement(
    val timestampSeconds: Int,
    val speaker: String,
    val side: SpeakerSide = SpeakerSide.OFFICER,
    val quote: String,
    val verdict: StatementVerdict,
    val citation: String,
    val explanation: String
)

data class WhatIfGuide(
    val whatYouCanRefuse: List<String> = emptyList(),
    val whatYouMustDo: List<String> = emptyList(),
    val exactScriptToSay: String = ""
)

data class AuditReport(
    val id: String,
    val title: String,
    val date: String,
    val sourceUrl: String? = null,
    val videoUriString: String? = null,
    val encounterType: EncounterType,
    val jurisdiction: UkJurisdiction = UkJurisdiction.ENGLAND_WALES,
    val complianceScore: Int,
    val scoreSummary: String = "",
    val officerViolationCount: Int,
    val staffViolationCount: Int = 0,
    val publicMythCount: Int = 0,
    val fullTranscript: List<TranscriptLine> = emptyList(),
    val yourRights: List<String> = emptyList(),
    val whatIfGuide: WhatIfGuide = WhatIfGuide(),
    val gowiselyChecklist: List<GowiselyItem>,
    val timeline: List<AuditedStatement>,
    val takeaway: String,
    val isExample: Boolean = false
) {
    val violationCount: Int get() = officerViolationCount + staffViolationCount
}

data class DocumentRedFlag(
    val severity: String, // "HIGH", "MEDIUM", "INFO"
    val clause: String,
    val issue: String,
    val citation: String
)

data class DocumentScanResult(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val documentType: String, // e.g. "Speeding NIP", "Parking PCN", "Tenancy Notice", "Contract / T&Cs"
    val dateScanned: String,
    val jurisdiction: UkJurisdiction = UkJurisdiction.ENGLAND_WALES,
    val summary: String,
    val keyDeadlines: List<String>,
    val redFlags: List<DocumentRedFlag>,
    val appealOrReplyLetter: String
)

data class StatuteLookupResult(
    val citation: String,
    val actTitle: String,
    val jurisdiction: String,
    val plainEnglishSummary: String,
    val keyExceptions: List<String>,
    val legislationUrl: String
)

enum class BookmarkFolder(val label: String) {
    POLICE_AND_PUBLIC("Police & Public Order"),
    MOTORING("Motoring & Dashcams"),
    CONSUMER("Consumer Refunds"),
    HOUSING("Renting & Housing"),
    EMPLOYMENT("Employment"),
    MYTHBUSTER("UK Law Myths")
}

data class SavedBookmark(
    val id: String = UUID.randomUUID().toString(),
    val folder: BookmarkFolder,
    val title: String,
    val content: String,
    val citation: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

object SampleAudits {
    val examples: List<AuditReport> = listOf(
        AuditReport(
            id = "example-1",
            title = "London Street Stop & Search",
            date = "12 Nov",
            sourceUrl = "https://www.tiktok.com/@example/video/1",
            encounterType = EncounterType.PEDESTRIAN,
            jurisdiction = UkJurisdiction.ENGLAND_WALES,
            complianceScore = 65,
            officerViolationCount = 2,
            publicMythCount = 1,
            isExample = true,
            yourRights = listOf(
                "You do not have to give your name or address during a stop and search (PACE Code A para 4.4).",
                "You are entitled to a copy of the search record immediately or within 12 months (PACE s.3).",
                "You can film police in public provided you do not physically obstruct the search (NPCC Guidance)."
            ),
            whatIfGuide = WhatIfGuide(
                whatYouCanRefuse = listOf(
                    "Giving your name, date of birth, or home address.",
                    "Stopping your recording on a public pavement."
                ),
                whatYouMustDo = listOf(
                    "Remain detained calmly while the search is carried out under s.23 Misuse of Drugs Act 1971.",
                    "Avoid physically resisting or blocking the officer (which risks arrest under s.89 Police Act 1996)."
                ),
                exactScriptToSay = "Officer, I am not resisting and I will comply with the search. Before we begin, please state your station, the legislation, and your grounds under GOWISELY. I choose not to provide my personal details."
            ),
            gowiselyChecklist = listOf(
                GowiselyItem("Grounds for search", GowiselyStatus.VERIFIED, "Officer cited smell and observed hand-off."),
                GowiselyItem("Object of search", GowiselyStatus.VERIFIED, "Stated searching for controlled substances."),
                GowiselyItem("Warrant card (if plain-clothed)", GowiselyStatus.SKIPPED, "Officer in full uniform."),
                GowiselyItem("Identity of officer", GowiselyStatus.VERIFIED, "Gave surname and shoulder number."),
                GowiselyItem("Station attached to", GowiselyStatus.FLAGGED, "Omitted police station before starting search."),
                GowiselyItem("Entitlement to copy of record", GowiselyStatus.FLAGGED, "Did not inform citizen of right to a search record."),
                GowiselyItem("Legislation being applied", GowiselyStatus.VERIFIED, "Cited Misuse of Drugs Act 1971 s.23."),
                GowiselyItem("You are detained for a search", GowiselyStatus.VERIFIED, "Explicitly stated citizen was detained.")
            ),
            timeline = listOf(
                AuditedStatement(
                    timestampSeconds = 14,
                    speaker = "Officer",
                    side = SpeakerSide.OFFICER,
                    quote = "You are detained for a search under Section 23 Misuse of Drugs Act.",
                    verdict = StatementVerdict.LAWFUL,
                    citation = "PACE Code A para 3.8",
                    explanation = "Clearly stating detention and the statutory power complies with PACE Code A."
                ),
                AuditedStatement(
                    timestampSeconds = 29,
                    speaker = "Citizen",
                    side = SpeakerSide.PUBLIC,
                    quote = "You cannot touch my pockets without my consent or a warrant!",
                    verdict = StatementVerdict.MISLEADING_CLAIM,
                    citation = "Misuse of Drugs Act 1971 s.23 / PACE s.117",
                    explanation = "Common UK myth: once an officer has reasonable grounds under s.23, they do not need your consent or a court warrant to search outer clothing and pockets."
                ),
                AuditedStatement(
                    timestampSeconds = 38,
                    speaker = "Officer",
                    side = SpeakerSide.OFFICER,
                    quote = "You have to give me your full name and date of birth right now.",
                    verdict = StatementVerdict.UNLAWFUL_DEMAND,
                    citation = "PACE 1984 Code A para 4.4",
                    explanation = "There is no legal requirement to provide personal details during a stop and search in England & Wales."
                ),
                AuditedStatement(
                    timestampSeconds = 62,
                    speaker = "Officer",
                    side = SpeakerSide.OFFICER,
                    quote = "Put the phone away, you can't film me while I'm working.",
                    verdict = StatementVerdict.MISLEADING_CLAIM,
                    citation = "NPCC Guidance on Filming in Public",
                    explanation = "Members of the public have the right to film officers in public places provided they do not physically obstruct the search."
                )
            ),
            takeaway = "The officer had lawful power to search under s.23 without a warrant, so the citizen's consent claim was wrong. However, the officer breached GOWISELY by omitting their station and record entitlement, and had no legal power to demand ID or stop public filming."
        )
    )
}
