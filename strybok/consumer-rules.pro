# The Strybok SDK uses no reflection on its own classes and no serialization by class name, so it
# needs no keep rules.
#
# PlayIntegrityTokenProvider is compiled against com.google.android.play:integrity, which the SDK
# does not bring along. Apps that do not use attestation do not have that library; tell R8 that
# the missing classes are expected.
-dontwarn com.google.android.play.core.integrity.**
-dontwarn com.google.android.gms.tasks.**
