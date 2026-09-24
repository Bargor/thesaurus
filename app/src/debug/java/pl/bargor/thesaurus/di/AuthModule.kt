package pl.bargor.thesaurus.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.bargor.thesaurus.data.auth.AuthRepository
import pl.bargor.thesaurus.data.auth.FirebaseGoogleAuthRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds @Singleton
    abstract fun bindAuthRepository(repository: FirebaseGoogleAuthRepository): AuthRepository
}
