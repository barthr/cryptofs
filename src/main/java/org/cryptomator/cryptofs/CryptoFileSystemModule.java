/*******************************************************************************
 * Copyright (c) 2017 Skymatic UG (haftungsbeschränkt).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE file.
 *******************************************************************************/
package org.cryptomator.cryptofs;

import dagger.Module;
import dagger.Provides;
import org.cryptomator.cryptofs.attr.AttributeComponent;
import org.cryptomator.cryptofs.attr.AttributeViewComponent;
import org.cryptomator.cryptofs.common.Constants;
import org.cryptomator.cryptofs.common.MasterkeyBackupFileHasher;
import org.cryptomator.cryptofs.dir.DirectoryStreamComponent;
import org.cryptomator.cryptofs.fh.OpenCryptoFileComponent;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.KeyFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Arrays;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Module(subcomponents = {AttributeComponent.class, AttributeViewComponent.class, OpenCryptoFileComponent.class, DirectoryStreamComponent.class})
class CryptoFileSystemModule {

	private static final Logger LOG = LoggerFactory.getLogger(CryptoFileSystemModule.class);

	@Provides
	@CryptoFileSystemScoped
	public Cryptor provideCryptor(CryptorProvider cryptorProvider, @PathToVault Path pathToVault, CryptoFileSystemProperties properties, ReadonlyFlag readonlyFlag) {
		try {
			Path masterKeyPath = pathToVault.resolve(properties.masterkeyFilename());
			assert Files.exists(masterKeyPath); // since 1.3.0 a file system can only be created for existing vaults. initialization is done before.
			byte[] keyFileContents = Files.readAllBytes(masterKeyPath);
			Path backupKeyPath = pathToVault.resolve(properties.masterkeyFilename() + MasterkeyBackupFileHasher.generateFileIdSuffix(keyFileContents) + Constants.MASTERKEY_BACKUP_SUFFIX);
			Cryptor cryptor = cryptorProvider.createFromKeyFile(KeyFile.parse(keyFileContents), properties.passphrase(), properties.pepper(), Constants.VAULT_VERSION);
			try {
				backupMasterkeyFileIfRequired(masterKeyPath, backupKeyPath, readonlyFlag);
			} catch (IOException e) {
				bitwiseCompareMasterkeyToExistingBackup(keyFileContents, backupKeyPath);
			}
			return cryptor;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Provides
	@CryptoFileSystemScoped
	public Optional<FileStore> provideNativeFileStore(@PathToVault Path pathToVault) {
		try {
			return Optional.of(Files.getFileStore(pathToVault));
		} catch (IOException e) {
			LOG.warn("Failed to get file store for " + pathToVault, e);
			return Optional.empty();
		}
	}

	private void bitwiseCompareMasterkeyToExistingBackup(byte [] keyFileContents, Path backupKeyPath) throws IOException {
		byte[] backupContents = Files.readAllBytes(backupKeyPath);
		if (!Arrays.equals(keyFileContents, backupContents)) {
			throw new IllegalStateException("Backup masterkey file does not match working masterkey file, but cannot be replaced: Storage is read-only.");
		}
	}

	private void backupMasterkeyFileIfRequired(Path masterKeyPath, Path backupKeyPath, ReadonlyFlag readonlyFlag) throws IOException {
		if (!readonlyFlag.isSet()) {
			Files.copy(masterKeyPath, backupKeyPath, REPLACE_EXISTING);
		}
	}

}
