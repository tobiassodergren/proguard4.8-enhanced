/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2012 Eric Lafortune (eric@graphics.cornell.edu)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard;

import proguard.classfile.ClassPool;
import proguard.classfile.attribute.visitor.AllAttributeVisitor;
import proguard.classfile.constant.visitor.*;
import proguard.classfile.instruction.visitor.AllInstructionVisitor;
import proguard.classfile.util.*;
import proguard.classfile.visitor.*;
import proguard.util.*;

import java.io.IOException;
import java.util.*;

/**
 * This class initializes class pools.
 *
 * @author Eric Lafortune
 */
public class Initializer
{
    private final Configuration configuration;


    /**
     * Creates a new Initializer to initialize classes according to the given
     * configuration.
     */
    public Initializer(Configuration configuration)
    {
        this.configuration = configuration;
    }


    /**
     * Initializes the classes in the given program class pool and library class
     * pool, performs some basic checks, and shrinks the library class pool.
     */
    public void execute(ClassPool programClassPool,
                        ClassPool libraryClassPool) throws IOException
    {
        int originalLibraryClassPoolSize = libraryClassPool.size();

        // Perform a basic check on the keep options in the configuration.
        WarningPrinter keepClassMemberNotePrinter = new WarningPrinter(System.out, configuration.note);

        new KeepClassMemberChecker(keepClassMemberNotePrinter).checkClassSpecifications(configuration.keep);

        // Construct a reduced library class pool with only those library
        // classes whose hierarchies are referenced by the program classes.
        // We can't do this if we later have to come up with the obfuscated
        // class member names that are globally unique.
        ClassPool reducedLibraryClassPool = configuration.useUniqueClassMemberNames ?
            null : new ClassPool();

        WarningPrinter classReferenceWarningPrinter = new WarningPrinter(System.err, configuration.warn);
        WarningPrinter dependencyWarningPrinter     = new WarningPrinter(System.err, configuration.warn);

        // Initialize the superclass hierarchies for program classes.
        programClassPool.classesAccept(
            new ClassSuperHierarchyInitializer(programClassPool,
                                               libraryClassPool,
                                               classReferenceWarningPrinter,
                                               null));

        // Initialize the superclass hierarchy of all library classes, without
        // warnings.
        libraryClassPool.classesAccept(
            new ClassSuperHierarchyInitializer(programClassPool,
                                               libraryClassPool,
                                               null,
                                               dependencyWarningPrinter));

        // Initialize the class references of program class members and
        // attributes. Note that all superclass hierarchies have to be
        // initialized for this purpose.
        WarningPrinter memberReferenceWarningPrinter = new WarningPrinter(System.err, configuration.warn);

        programClassPool.classesAccept(
            new ClassReferenceInitializer(programClassPool,
                                          libraryClassPool,
                                          classReferenceWarningPrinter,
                                          memberReferenceWarningPrinter,
                                          null));

        if (reducedLibraryClassPool != null)
        {
            // Collect the library classes that are directly referenced by
            // program classes, without introspection.
            programClassPool.classesAccept(
                new ReferencedClassVisitor(
                new LibraryClassFilter(
                new ClassPoolFiller(reducedLibraryClassPool))));

            // Reinitialize the superclass hierarchies of referenced library
            // classes, this time with warnings.
            reducedLibraryClassPool.classesAccept(
                new ClassSuperHierarchyInitializer(programClassPool,
                                                   libraryClassPool,
                                                   classReferenceWarningPrinter,
                                                   null));
        }

        if (classReferenceWarningPrinter.getWarningCount() > 0 && configuration.preverifyStopAfterWarnings) {
            throw new IOException("There were warnings and preverifier was configured to stop.");
        }

        // Initialize the Class.forName references.
        WarningPrinter dynamicClassReferenceNotePrinter = new WarningPrinter(System.out, configuration.note);
        WarningPrinter classForNameNotePrinter          = new WarningPrinter(System.out, configuration.note);

        programClassPool.classesAccept(
            new AllMethodVisitor(
            new AllAttributeVisitor(
            new AllInstructionVisitor(
            new DynamicClassReferenceInitializer(programClassPool,
                                                 libraryClassPool,
                                                 dynamicClassReferenceNotePrinter,
                                                 null,
                                                 classForNameNotePrinter,
                                                 createClassNoteExceptionMatcher(configuration.keep))))));

        // Initialize the Class.get[Declared]{Field,Method} references.
        WarningPrinter getMemberNotePrinter = new WarningPrinter(System.out, configuration.note);

        programClassPool.classesAccept(
            new AllMethodVisitor(
            new AllAttributeVisitor(
            new AllInstructionVisitor(
            new DynamicMemberReferenceInitializer(programClassPool,
                                                  libraryClassPool,
                                                  getMemberNotePrinter,
                                                  createClassMemberNoteExceptionMatcher(configuration.keep, true),
                                                  createClassMemberNoteExceptionMatcher(configuration.keep, false))))));

        // Initialize other string constant references, if requested.
        if (configuration.adaptClassStrings != null)
        {
            programClassPool.classesAccept(
                new ClassNameFilter(configuration.adaptClassStrings,
                new AllConstantVisitor(
                new StringReferenceInitializer(programClassPool,
                                               libraryClassPool))));
        }

        // Print various notes, if specified.
        WarningPrinter fullyQualifiedClassNameNotePrinter = new WarningPrinter(System.out, configuration.note);
        WarningPrinter descriptorKeepNotePrinter          = new WarningPrinter(System.out, configuration.note);

        new FullyQualifiedClassNameChecker(programClassPool,
                                           libraryClassPool,
                                           fullyQualifiedClassNameNotePrinter).checkClassSpecifications(configuration.keep);

        new DescriptorKeepChecker(programClassPool,
                                  libraryClassPool,
                                  descriptorKeepNotePrinter).checkClassSpecifications(configuration.keep);

        // Initialize the class references of library class members.
        if (reducedLibraryClassPool != null)
        {
            // Collect the library classes that are referenced by program
            // classes, directly or indirectly, with or without introspection.
            programClassPool.classesAccept(
                new ReferencedClassVisitor(
                new LibraryClassFilter(
                new ClassHierarchyTraveler(true, true, true, false,
                new LibraryClassFilter(
                new ClassPoolFiller(reducedLibraryClassPool))))));

            // Initialize the class references of referenced library
            // classes, without warnings.
            reducedLibraryClassPool.classesAccept(
                new ClassReferenceInitializer(programClassPool,
                                              libraryClassPool,
                                              null,
                                              null,
                                              dependencyWarningPrinter));

            // Reset the library class pool.
            libraryClassPool.clear();

            // Copy the library classes that are referenced directly by program
            // classes and the library classes that are referenced by referenced
            // library classes.
            reducedLibraryClassPool.classesAccept(
                new MultiClassVisitor(new ClassVisitor[]
                {
                    new ClassHierarchyTraveler(true, true, true, false,
                    new LibraryClassFilter(
                    new ClassPoolFiller(libraryClassPool))),

                    new ReferencedClassVisitor(
                    new LibraryClassFilter(
                    new ClassHierarchyTraveler(true, true, true, false,
                    new LibraryClassFilter(
                    new ClassPoolFiller(libraryClassPool)))))
                }));
        }
        else
        {
            // Initialize the class references of all library class members.
            libraryClassPool.classesAccept(
                new ClassReferenceInitializer(programClassPool,
                                              libraryClassPool,
                                              null,
                                              null,
                                              dependencyWarningPrinter));
        }

        // Initialize the subclass hierarchies.
        programClassPool.classesAccept(new ClassSubHierarchyInitializer());
        libraryClassPool.classesAccept(new ClassSubHierarchyInitializer());

        // Share strings between the classes, to reduce heap memory usage.
        programClassPool.classesAccept(new StringSharer());
        libraryClassPool.classesAccept(new StringSharer());

        // Print out a summary of the notes, if necessary.
        int fullyQualifiedNoteCount = fullyQualifiedClassNameNotePrinter.getWarningCount();
        if (fullyQualifiedNoteCount > 0)
        {
            System.out.println("Note: there were " + fullyQualifiedNoteCount +
                               " references to unknown classes.");
            System.out.println("      You should check your configuration for typos.");
        }

        int descriptorNoteCount = descriptorKeepNotePrinter.getWarningCount();
        if (descriptorNoteCount > 0)
        {
            System.out.println("Note: there were " + descriptorNoteCount +
                               " unkept descriptor classes in kept class members.");
            System.out.println("      You should consider explicitly keeping the mentioned classes");
            System.out.println("      (using '-keep').");
        }

        int dynamicClassReferenceNoteCount = dynamicClassReferenceNotePrinter.getWarningCount();
        if (dynamicClassReferenceNoteCount > 0)
        {
            System.out.println("Note: there were " + dynamicClassReferenceNoteCount +
                               " unresolved dynamic references to classes or interfaces.");
            System.out.println("      You should check if you need to specify additional program jars.");
        }

        int classForNameNoteCount = classForNameNotePrinter.getWarningCount();
        if (classForNameNoteCount > 0)
        {
            System.out.println("Note: there were " + classForNameNoteCount +
                               " class casts of dynamically created class instances.");
            System.out.println("      You might consider explicitly keeping the mentioned classes and/or");
            System.out.println("      their implementations (using '-keep').");
        }

        int getmemberNoteCount = getMemberNotePrinter.getWarningCount();
        if (getmemberNoteCount > 0)
        {
            System.out.println("Note: there were " + getmemberNoteCount +
                               " accesses to class members by means of introspection.");
            System.out.println("      You should consider explicitly keeping the mentioned class members");
            System.out.println("      (using '-keep' or '-keepclassmembers').");
        }

        // Print out a summary of the warnings, if necessary.
        int classReferenceWarningCount = classReferenceWarningPrinter.getWarningCount();
        if (classReferenceWarningCount > 0)
        {
            System.err.println("Warning: there were " + classReferenceWarningCount +
                               " unresolved references to classes or interfaces.");
            System.err.println("         You may need to specify additional library jars (using '-libraryjars').");

            if (configuration.skipNonPublicLibraryClasses)
            {
                System.err.println("         You may also have to remove the option '-skipnonpubliclibraryclasses'.");
            }
        }

        int dependencyWarningCount = dependencyWarningPrinter.getWarningCount();
        if (dependencyWarningCount > 0)
        {
            System.err.println("Warning: there were " + dependencyWarningCount +
                               " instances of library classes depending on program classes.");
            System.err.println("         You must avoid such dependencies, since the program classes will");
            System.err.println("         be processed, while the library classes will remain unchanged.");
        }

        int memberReferenceWarningCount = memberReferenceWarningPrinter.getWarningCount();
        if (memberReferenceWarningCount > 0)
        {
            System.err.println("Warning: there were " + memberReferenceWarningCount +
                               " unresolved references to program class members.");
            System.err.println("         Your input classes appear to be inconsistent.");
            System.err.println("         You may need to recompile them and try again.");
            System.err.println("         Alternatively, you may have to specify the option ");
            System.err.println("         '-dontskipnonpubliclibraryclassmembers'.");

            if (configuration.skipNonPublicLibraryClasses)
            {
                System.err.println("         You may also have to remove the option '-skipnonpubliclibraryclasses'.");
            }
        }

        if ((classReferenceWarningCount   > 0 ||
             dependencyWarningCount       > 0 ||
             memberReferenceWarningCount  > 0) &&
            !configuration.ignoreWarnings)
        {
            throw new IOException("Please correct the above warnings first.");
        }

        if ((configuration.note == null ||
             !configuration.note.isEmpty()) &&
            (configuration.warn != null &&
             configuration.warn.isEmpty() ||
             configuration.ignoreWarnings))
        {
            System.out.println("Note: You're ignoring all warnings!");
        }

        // Discard unused library classes.
        if (configuration.verbose)
        {
            System.out.println("Ignoring unused library classes...");
            System.out.println("  Original number of library classes: " + originalLibraryClassPoolSize);
            System.out.println("  Final number of library classes:    " + libraryClassPool.size());
        }
    }


    /**
     * Extracts a list of exceptions of classes for which not to print notes,
     * from the keep configuration.
     */
    private StringMatcher createClassNoteExceptionMatcher(List noteExceptions)
    {
        if (noteExceptions != null)
        {
            List noteExceptionNames = new ArrayList(noteExceptions.size());
            for (int index = 0; index < noteExceptions.size(); index++)
            {
                KeepClassSpecification keepClassSpecification = (KeepClassSpecification)noteExceptions.get(index);
                if (keepClassSpecification.markClasses)
                {
                    // If the class itself is being kept, it's ok.
                    String className = keepClassSpecification.className;
                    if (className != null)
                    {
                        noteExceptionNames.add(className);
                    }

                    // If all of its extensions are being kept, it's ok too.
                    String extendsClassName = keepClassSpecification.extendsClassName;
                    if (extendsClassName != null)
                    {
                        noteExceptionNames.add(extendsClassName);
                    }
                }
            }

            if (noteExceptionNames.size() > 0)
            {
                return new ListParser(new ClassNameParser()).parse(noteExceptionNames);
            }
        }

        return null;
    }


    /**
     * Extracts a list of exceptions of field or method names for which not to
     * print notes, from the keep configuration.
     */
    private StringMatcher createClassMemberNoteExceptionMatcher(List    noteExceptions,
                                                                boolean isField)
    {
        if (noteExceptions != null)
        {
            List noteExceptionNames = new ArrayList();
            for (int index = 0; index < noteExceptions.size(); index++)
            {
                KeepClassSpecification keepClassSpecification = (KeepClassSpecification)noteExceptions.get(index);
                List memberSpecifications = isField ?
                    keepClassSpecification.fieldSpecifications :
                    keepClassSpecification.methodSpecifications;

                if (memberSpecifications != null)
                {
                    for (int index2 = 0; index2 < memberSpecifications.size(); index2++)
                    {
                        MemberSpecification memberSpecification =
                            (MemberSpecification)memberSpecifications.get(index2);

                        String memberName = memberSpecification.name;
                        if (memberName != null)
                        {
                            noteExceptionNames.add(memberName);
                        }
                    }
                }
            }

            if (noteExceptionNames.size() > 0)
            {
                return new ListParser(new ClassNameParser()).parse(noteExceptionNames);
            }
        }

        return null;
    }
}
