package com.memfault.bort.ota.lib

import com.squareup.anvil.annotations.compat.MergeModules
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Binds Anvil (for ContributesBinding) to Hilt's component.
@MergeModules(SingletonComponent::class)
@InstallIn(SingletonComponent::class)
class OtaAnvil
