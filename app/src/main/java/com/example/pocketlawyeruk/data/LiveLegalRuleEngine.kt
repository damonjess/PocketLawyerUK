package com.example.pocketlawyeruk.data

import com.example.pocketlawyeruk.model.UkJurisdiction

enum class LivePartyMode(val label: String) {
    POLICE("Police"),
    STAFF("Staff / Sec")
}

data class LiveRightsCard(
    val id: String,
    val title: String,
    val statute: String,
    val yourRight: String,
    val sayThis: String,
    val isHighAlert: Boolean = false
)

data class LiveCoachAdvice(
    val canRefuse: String,
    val mustComply: String,
    val sayNow: String,
    val statute: String
)

data class GowiselyStepKey(val letter: String, val name: String, val keywords: List<String>)

object LiveLegalRuleEngine {

    val gowiselySteps = listOf(
        GowiselyStepKey("G", "Grounds", listOf("grounds", "reason", "smell", "suspicion", "suspect you")),
        GowiselyStepKey("O", "Object", listOf("looking for", "searching for", "object of", "drugs", "weapon", "knife", "stolen")),
        GowiselyStepKey("W", "Warrant", listOf("warrant card", "plain clothes", "badge")),
        GowiselyStepKey("I", "Identity", listOf("collar number", "shoulder number", "my name is", "officer", "constable", "pc ")),
        GowiselyStepKey("S", "Station", listOf("station", "based at", "attached to")),
        GowiselyStepKey("E", "Entitlement", listOf("copy of", "search record", "receipt", "12 months")),
        GowiselyStepKey("L", "Legislation", listOf("section 1", "section 23", "pace", "misuse of drugs", "section 60", "power under")),
        GowiselyStepKey("Y", "You're Detained", listOf("detained for a search", "you are detained", "detaining you"))
    )

    private data class TriggerRule(
        val id: String,
        val keywords: List<String>,
        val partyFilter: LivePartyMode? = null, // null = applies to both Police & Staff
        val buildCard: (LivePartyMode, UkJurisdiction) -> LiveRightsCard
    )

    private val rules = listOf(
        TriggerRule(
            id = "id_demand_police",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("your name", "date of birth", "address", "details", "who are you", "identify yourself", "got id", "show me id"),
            buildCard = { _, jur ->
                if (jur == UkJurisdiction.SCOTLAND) {
                    LiveRightsCard(
                        id = "id_demand_police",
                        title = "SCOTLAND ID DUTY (s.13)",
                        statute = "Criminal Procedure (Scotland) Act 1995 s.13",
                        yourRight = "In Scotland, police CAN require your name, address, DOB, and place of birth if they suspect you of an offence OR as a witness.",
                        sayThis = "Officer, are you requiring my details under Section 13 as a suspect or a witness? If so, I will provide them.",
                        isHighAlert = true
                    )
                } else {
                    LiveRightsCard(
                        id = "id_demand_police",
                        title = "NO GENERAL DUTY TO GIVE ID",
                        statute = "PACE 1984 Code A para 4.4 / PRA 2002 s.50",
                        yourRight = "You do NOT have to give your name, address, or DOB unless driving a vehicle (RTA s.163/164), arrested, or reasonably suspected of anti-social behaviour (s.50).",
                        sayThis = "I do not wish to give my personal details unless you are citing a specific statutory power requiring them, such as Section 50 or the Road Traffic Act.",
                        isHighAlert = true
                    )
                }
            }
        ),
        TriggerRule(
            id = "id_demand_staff",
            partyFilter = LivePartyMode.STAFF,
            keywords = listOf("your name", "details", "who are you", "who do you work for", "show me id", "identification"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "id_demand_staff",
                    title = "STAFF CANNOT DEMAND ID",
                    statute = "Common Law (No Statutory Power)",
                    yourRight = "Private staff and security guards have zero legal power to demand your name, ID, or why you are filming from public land.",
                    sayThis = "I am under no legal obligation to give my personal details to private staff."
                )
            }
        ),
        TriggerRule(
            id = "section_50",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("section 50", "anti-social", "antisocial", "harassment alarm or distress"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "section_50",
                    title = "SECTION 50 ASB DEMAND",
                    statute = "Police Reform Act 2002 s.50",
                    yourRight = "An officer must have reasonable belief you ACTUALLY engaged in anti-social behaviour. Simply filming in public is not ASB — but refusing a valid s.50 demand is an arrestable offence.",
                    sayThis = "Please state clearly what specific anti-social behaviour you believe I have committed to trigger Section 50.",
                    isHighAlert = true
                )
            }
        ),
        TriggerRule(
            id = "filming_public",
            keywords = listOf("can't film", "cannot film", "stop filming", "permission to film", "not allowed to film", "illegal to film", "turn that off", "camera off", "stop recording", "no photography", "authorisation to film", "authorization to film"),
            buildCard = { party, _ ->
                LiveRightsCard(
                    id = "filming_public",
                    title = "RIGHT TO FILM IN PUBLIC",
                    statute = "NPCC Filming Guidance & Common Law",
                    yourRight = "There is no UK law banning filming people, buildings, or police from a public road or pavement, and no consent or permit is needed.",
                    sayThis = if (party == LivePartyMode.POLICE)
                        "I am standing on public land and filming lawfully under NPCC guidance without obstructing your duties."
                    else
                        "I am filming from public land where UK law does not require permission or consent."
                )
            }
        ),
        TriggerRule(
            id = "gdpr_privacy",
            keywords = listOf("gdpr", "data protection", "privacy", "human rights", "consent", "without permission", "number plates", "reg plates"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "gdpr_privacy",
                    title = "GDPR & PUBLIC PRIVACY MYTH",
                    statute = "Data Protection Act 2018 / HRA 1998 Art 8",
                    yourRight = "Personal/journalistic filming from a public road does not breach UK GDPR, and people or vehicles visible from public land have no reasonable expectation of privacy.",
                    sayThis = "There is no reasonable expectation of privacy in public view, and personal filming from a public highway does not breach GDPR."
                )
            }
        ),
        TriggerRule(
            id = "delete_seize",
            keywords = listOf("delete", "give me the phone", "hand over", "take your phone", "seize", "confiscate", "wipe that"),
            buildCard = { party, _ ->
                if (party == LivePartyMode.POLICE) {
                    LiveRightsCard(
                        id = "delete_seize",
                        title = "POLICE CANNOT DELETE FOOTAGE",
                        statute = "PACE 1984 s.19 / NPCC Guidance",
                        yourRight = "Officers have NO power to order you to delete footage on scene. They can only seize a device with a specific statutory power (e.g., as evidence of an offence) or warrant.",
                        sayThis = "I will not delete any footage. Please state the exact statutory power if you are seizing my device.",
                        isHighAlert = true
                    )
                } else {
                    LiveRightsCard(
                        id = "delete_seize",
                        title = "STAFF CANNOT TOUCH OR DELETE",
                        statute = "Theft Act 1968 / Criminal Justice Act 1988 s.39",
                        yourRight = "Private staff/security have zero right to demand deletion or grab your phone/drone. Physically taking it is assault or theft.",
                        sayThis = "You have no legal authority to demand I delete footage or touch my property.",
                        isHighAlert = true
                    )
                }
            }
        ),
        TriggerRule(
            id = "stop_search",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("stop and search", "detained for a search", "search you", "section 1", "section 23", "empty your pockets"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "stop_search",
                    title = "STOP & SEARCH — DEMAND GOWISELY",
                    statute = "PACE 1984 s.2 & Code A",
                    yourRight = "Do not physically resist, but before searching the officer MUST give: Grounds, Object, Warrant card (if plain-clothed), Identity, Station, Entitlement to record, Legislation, and Detention.",
                    sayThis = "I am not resisting. Before you search me, please state your Grounds, Object, Identity, Station, and the exact Legislation under GOWISELY.",
                    isHighAlert = true
                )
            }
        ),
        TriggerRule(
            id = "section_43",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("section 43", "terrorism", "hostile reconnaissance", "terrorist"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "section_43",
                    title = "SECTION 43 TERRORISM ACT",
                    statute = "Terrorism Act 2000 s.43",
                    yourRight = "Requires genuine reasonable suspicion YOU are a terrorist — filming a building alone is NOT enough. Officers still cannot delete photos/videos on the spot.",
                    sayThis = "Are you formally detaining me under Section 43, and what are your specific reasonable grounds to suspect I am a terrorist?",
                    isHighAlert = true
                )
            }
        ),
        TriggerRule(
            id = "trespass_private",
            keywords = listOf("private land", "private property", "trespass", "get off our land", "ask you to leave", "leave the premises"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "trespass_private",
                    title = "PRIVATE LAND & TRESPASS",
                    statute = "Common Law Trespass",
                    yourRight = "A landowner or their staff CAN withdraw permission to stand on private property (simple trespass is civil, not criminal). Step calmly onto the public pavement/highway.",
                    sayThis = "If I am on private land, show me the boundary and I will step straight onto the public pavement."
                )
            }
        ),
        TriggerRule(
            id = "drone_airspace",
            keywords = listOf("drone", "airspace", "no fly", "bring it down", "land it", "flying over", "caa"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "drone_airspace",
                    title = "DRONE & UK AIRSPACE RULES",
                    statute = "CAA Air Navigation Order 2016",
                    yourRight = "Landowners control take-off and landing on their land, NOT the airspace above it (unless a formal CAA Flight Restriction Zone / NOTAM applies). Keep VLOS and CAA safe distances.",
                    sayThis = "I took off from public land and am flying in compliance with CAA regulations. Landowners do not control UK airspace."
                )
            }
        ),
        TriggerRule(
            id = "damage_threat",
            keywords = listOf("throw something", "break it", "smash it", "knock it down", "shoot it", "hit it"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "damage_threat",
                    title = "THREAT OF CRIMINAL DAMAGE",
                    statute = "Criminal Damage Act 1971 s.2",
                    yourRight = "Threatening to destroy or damage someone else's property (including a drone or camera) without lawful excuse is a criminal offence under s.2.",
                    sayThis = "Threatening to damage my equipment is a criminal offence under Section 2 of the Criminal Damage Act 1971, and this is being recorded.",
                    isHighAlert = true
                )
            }
        ),
        TriggerRule(
            id = "vehicle_stop",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("driving licence", "driver's licence", "insurance", "section 163", "section 164", "pull over", "breathalyser"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "vehicle_stop",
                    title = "VEHICLE STOP DUTIES",
                    statute = "Road Traffic Act 1988 s.163 & s.164",
                    yourRight = "When driving on a road, you MUST stop for a uniformed constable and provide your name, address, DOB, licence, and insurance details (or produce within 7 days).",
                    sayThis = "I am happy to comply with Section 164 and provide my driving details.",
                    isHighAlert = true
                )
            }
        ),
        TriggerRule(
            id = "obstruction",
            partyFilter = LivePartyMode.POLICE,
            keywords = listOf("obstructing", "obstruction", "interfering", "step back", "cordon"),
            buildCard = { _, _ ->
                LiveRightsCard(
                    id = "obstruction",
                    title = "AVOIDING OBSTRUCTION (s.89)",
                    statute = "Police Act 1996 s.89(2)",
                    yourRight = "Filming from a safe distance is NOT wilful obstruction, but physically blocking officers or crossing a lawful cordon risks arrest. Take two steps back while continuing to film.",
                    sayThis = "I am stepping back to give you plenty of space so I am not obstructing your work."
                )
            }
        )
    )

    fun detectMatchingCards(
        text: String,
        partyMode: LivePartyMode,
        jurisdiction: UkJurisdiction
    ): List<LiveRightsCard> {
        val lower = text.lowercase()
        return rules
            .filter { rule ->
                (rule.partyFilter == null || rule.partyFilter == partyMode) &&
                    rule.keywords.any { kw -> lower.contains(kw) }
            }
            .map { it.buildCard(partyMode, jurisdiction) }
    }

    fun detectGowiselyLetters(text: String): Set<String> {
        val lower = text.lowercase()
        return gowiselySteps
            .filter { step -> step.keywords.any { kw -> lower.contains(kw) } }
            .map { it.letter }
            .toSet()
    }
}