package se.soderbjorn.notegrow

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform