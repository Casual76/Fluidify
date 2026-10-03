# kotlinx.serialization ships its own consumer rules; the protocol classes are
# reached only through their generated serializers, which those rules keep.

# The listener service is instantiated by Play Services by name.
-keep class dev.pampa.fluidify.wear.link.WatchListenerService { *; }
