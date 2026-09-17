package pl.bargor.thesaurus.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.bargor.thesaurus.data.auth.AuthRepository
import pl.bargor.thesaurus.data.auth.FirebaseGoogleAuthRepository
import pl.bargor.thesaurus.data.firebase.FirebaseAuthFactory
import pl.bargor.thesaurus.data.firebase.FirebaseFirestoreFactory
import pl.bargor.thesaurus.data.firebase.FirestoreRepositories
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.OnboardingRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestoreFactory.create()

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuthFactory.create()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindAuthRepository(repository: FirebaseGoogleAuthRepository): AuthRepository

    @Binds @Singleton
    abstract fun bindOnboardingRepository(repository: FirestoreRepositories): OnboardingRepository

    @Binds @Singleton
    abstract fun bindTaxonomyRepository(repository: FirestoreRepositories): TaxonomyRepository

    @Binds @Singleton
    abstract fun bindLedgerRepository(repository: FirestoreRepositories): LedgerRepository
}
