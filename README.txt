Balance HUD (Fabric 26.1.2, client-side)

BUILD
  1. Install JDK 25.
  2. In this folder run:  gradle build     (needs Gradle 9+; or copy in a gradle wrapper from the Fabric example mod)
  3. Take build/libs/balancehud-1.0.0.jar (NOT the -sources one) and put it in .minecraft/mods
     together with Fabric API 0.145.4+26.1.2 and Fabric Loader 0.19.1+.

HOW IT WORKS
  Run /bal (or /balance) yourself. The mod reads the first $ amount in the reply (supports K/M/B/T)
  and updates the HUD. The reply still shows in chat. Right side of the bottom line = change since you joined.
  Edit NAME / X / Y at the top of BalanceHudClient.java.
